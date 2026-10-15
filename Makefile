# Android app for the IP Webcam (native counterpart of the PWA: same hub protocol, but runs as a foreground service).
# Plain make + the Android SDK command-line tools; no Gradle. See docs/ANDROID.md and docs/TODO.md.
#
#   make sdk              one-time: download the Android SDK command-line tools + platform/build-tools (needs JDK 17+, internet)
#   make apk              build + sign build/android/webcam-<version>.apk
#   make install          adb install -r -g            [ADB_FLAGS="-s SERIAL"]
#   make provision        adb: grant permissions, battery whitelist, background appops     [OVERLAY=1 STAY_AWAKE=1 for the optional extras]
#   make dev              apk + install + provision + run
#   make run | stop | kill | logs | shot | uninstall | devices | wifi-adb PHONE_IP=...
#   make phone-info       model / Android version / lock state / camera users / permissions / battery whitelist
#   make provision-status    check what provision set up
#   make lock | wake      screen off+lock / wake  (for the lock-screen test)
#   make watch            babysit the phone from here: restart the app if the camera service is not in the foreground
#   make check            compile-check everything against android.jar (no SDK needed)
#   make test-protocol    the real Java protocol code against the real Python hub (no SDK, no phone)
#   make deploy-gil       rsync the tree to the build/test machine and build+install there   [REMOTE=printer@gil.home]
APP_ID       := io.github.dnscribe.webcam
VERSION      := $(shell sed -n 's/.*APP_VERSION = "\([^"]*\)".*/\1/p' version.js)
VERSION_CODE := $(shell echo $(VERSION) | awk -F. '{ print $$1*10000 + $$2*100 + $$3 }')
API          ?= 34
MINSDK       ?= 26
BUILD        := build/android
APK          := $(BUILD)/webcam-$(VERSION).apk
ADB          ?= adb
ADB_FLAGS    ?=
REMOTE       ?= printer@gil.home
REMOTE_DIR   ?= webcam-build
WATCH_SECS   ?= 30
PHONE_IP     ?=
OVERLAY      ?=
STAY_AWAKE   ?=

# ---- Android SDK discovery ----
SDK ?= $(firstword $(wildcard $(ANDROID_HOME) $(ANDROID_SDK_ROOT) $(HOME)/Android/Sdk $(HOME)/Library/Android/sdk $(HOME)/android-sdk))
BT  ?= $(lastword $(sort $(notdir $(wildcard $(SDK)/build-tools/*))))
BTD := $(SDK)/build-tools/$(BT)
ANDROID_JAR := $(SDK)/platforms/android-$(API)/android.jar
AAPT2 := $(BTD)/aapt2
D8 := $(BTD)/d8
ZIPALIGN := $(BTD)/zipalign
APKSIGNER := $(BTD)/apksigner
ifneq ($(SDK),)
ADB := $(if $(wildcard $(SDK)/platform-tools/adb),$(SDK)/platform-tools/adb,adb)
endif

# ---- libraries (downloaded, checksum-verified) ----
MVN := https://repo.maven.apache.org/maven2
LIBDIR := $(BUILD)/libs
OKHTTP := $(LIBDIR)/okhttp-3.12.13.jar
OKIO := $(LIBDIR)/okio-1.17.2.jar
JSONJAR := $(LIBDIR)/json-20231013.jar
STUBS := $(LIBDIR)/android-all-14-robolectric-10818077.jar
OKHTTP_SHA := 508234e024ef7e270ab1a6d5b356f5b98e786511239ca986d684fd1e2cf7bc82
OKIO_SHA := f80ce42d2ffac47ad4c47e1d6f980d604d247ceb1a886705cf4581ab0c9fe2b8
JSON_SHA := 0f18192df289114e17aa1a0d0a7f8372cc9f5c7e4f7e39adcf8906fe714fa7d3
STUBS_SHA := 6be2218c6a53fe3c57bc22ebdc723edcb7270a8a6f187545708aa5c0ed813977

JAVA_SRC := $(shell find android/src -name '*.java')
CORE_SRC := $(shell find android/src/io/github/dnscribe/webcam/core -name '*.java')
RES_FILES := $(shell find android/res -type f)
KEYSTORE ?= android/debug.keystore
KS_PASS ?= android
KS_ALIAS ?= webcam
JAVAC := javac --release 11 -Xlint:-options -Xlint:-deprecation

.PHONY: help apk install run stop kill uninstall logs shot devices sdk check test-protocol clean deploy-gil libs sdk-check keystore \
        provision provision-status phone-info dev lock wake watch wifi-adb
.DEFAULT_GOAL := help

help:
	@awk '/^#/ { sub(/^# ?/, ""); print; next } { exit }' Makefile
	@echo; echo "version $(VERSION) (code $(VERSION_CODE)); SDK: $(if $(SDK),$(SDK) build-tools $(BT),NOT FOUND - run 'make sdk')"

# ------------------------------------------------------------------ libraries
$(OKHTTP):
	python3 android/tools/fetch.py $(MVN)/com/squareup/okhttp3/okhttp/3.12.13/okhttp-3.12.13.jar $@ $(OKHTTP_SHA)
$(OKIO):
	python3 android/tools/fetch.py $(MVN)/com/squareup/okio/okio/1.17.2/okio-1.17.2.jar $@ $(OKIO_SHA)
$(JSONJAR):
	python3 android/tools/fetch.py $(MVN)/org/json/json/20231013/json-20231013.jar $@ $(JSON_SHA)
$(STUBS):
	python3 android/tools/fetch.py $(MVN)/org/robolectric/android-all/14-robolectric-10818077/android-all-14-robolectric-10818077.jar $@ $(STUBS_SHA)

libs: $(OKHTTP) $(OKIO) $(JSONJAR)

# ------------------------------------------------------------------ SDK
sdk-check:
	@test -n "$(SDK)" || { echo "Android SDK not found. Run 'make sdk' (or set ANDROID_HOME / SDK=...)."; exit 1; }
	@test -f "$(ANDROID_JAR)" || { echo "Missing $(ANDROID_JAR). Run 'make sdk' or: sdkmanager \"platforms;android-$(API)\""; exit 1; }
	@test -x "$(AAPT2)" || { echo "Missing build-tools in $(SDK)/build-tools. Run 'make sdk' or: sdkmanager \"build-tools;34.0.0\""; exit 1; }

CMDLINE_OS := $(if $(filter Darwin,$(shell uname -s)),mac,linux)
CMDLINE_ZIP := commandlinetools-$(CMDLINE_OS)-11076708_latest.zip
sdk:
	@command -v java >/dev/null || { echo "A JDK (17+) is required."; exit 1; }
	$(eval SDKDIR := $(if $(SDK),$(SDK),$(HOME)/Android/Sdk))
	mkdir -p $(SDKDIR)/cmdline-tools $(BUILD)
	@test -x $(SDKDIR)/cmdline-tools/latest/bin/sdkmanager || { \
	  python3 android/tools/fetch.py https://dl.google.com/android/repository/$(CMDLINE_ZIP) $(BUILD)/$(CMDLINE_ZIP) && \
	  rm -rf $(BUILD)/cmdtools && mkdir -p $(BUILD)/cmdtools && cd $(BUILD)/cmdtools && python3 -c "import zipfile,sys; zipfile.ZipFile(sys.argv[1]).extractall('.')" ../$(CMDLINE_ZIP) && \
	  chmod -R +x cmdline-tools/bin && rm -rf $(SDKDIR)/cmdline-tools/latest && mv cmdline-tools $(SDKDIR)/cmdline-tools/latest; }
	yes | $(SDKDIR)/cmdline-tools/latest/bin/sdkmanager --sdk_root=$(SDKDIR) --licenses >/dev/null || true
	$(SDKDIR)/cmdline-tools/latest/bin/sdkmanager --sdk_root=$(SDKDIR) "platform-tools" "platforms;android-$(API)" "build-tools;34.0.0"
	@echo; echo "SDK ready in $(SDKDIR). Now: make apk"

# ------------------------------------------------------------------ build
keystore:
	@test -f $(KEYSTORE) || keytool -genkeypair -keystore $(KEYSTORE) -storepass $(KS_PASS) -keypass $(KS_PASS) -alias $(KS_ALIAS) \
	  -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Webcam debug,O=webcam" >/dev/null 2>&1

apk: $(APK)

$(APK): sdk-check libs keystore $(JAVA_SRC) $(RES_FILES) android/AndroidManifest.xml
	rm -rf $(BUILD)/classes $(BUILD)/dex $(BUILD)/res.zip $(BUILD)/base.apk $(BUILD)/unsigned.apk $(BUILD)/aligned.apk
	mkdir -p $(BUILD)/classes $(BUILD)/dex
	$(AAPT2) compile --dir android/res -o $(BUILD)/res.zip
	$(AAPT2) link -o $(BUILD)/base.apk -I $(ANDROID_JAR) --manifest android/AndroidManifest.xml \
	  --min-sdk-version $(MINSDK) --target-sdk-version $(API) --version-code $(VERSION_CODE) --version-name $(VERSION) $(BUILD)/res.zip
	$(JAVAC) -d $(BUILD)/classes -cp $(ANDROID_JAR):$(OKHTTP):$(OKIO) $(JAVA_SRC)
	jar cf $(BUILD)/classes.jar -C $(BUILD)/classes .
	$(D8) --release --lib $(ANDROID_JAR) --min-api $(MINSDK) --output $(BUILD)/dex $(BUILD)/classes.jar $(OKHTTP) $(OKIO)
	cp $(BUILD)/base.apk $(BUILD)/unsigned.apk
	python3 -c "import zipfile,sys; z=zipfile.ZipFile(sys.argv[1],'a'); z.write(sys.argv[2],'classes.dex'); z.close()" $(BUILD)/unsigned.apk $(BUILD)/dex/classes.dex
	$(ZIPALIGN) -f -p 4 $(BUILD)/unsigned.apk $(BUILD)/aligned.apk
	$(APKSIGNER) sign --ks $(KEYSTORE) --ks-pass pass:$(KS_PASS) --key-pass pass:$(KS_PASS) --out $@ $(BUILD)/aligned.apk
	@echo; echo "built $@"; ls -l $@

# ------------------------------------------------------------------ device
devices:
	$(ADB) devices -l

install: $(APK)
	$(ADB) $(ADB_FLAGS) install -r -g $(APK)

run:
	$(ADB) $(ADB_FLAGS) shell am start -n $(APP_ID)/.MainActivity

stop:
	$(ADB) $(ADB_FLAGS) shell am force-stop $(APP_ID)

uninstall:
	$(ADB) $(ADB_FLAGS) uninstall $(APP_ID)

logs:
	$(ADB) $(ADB_FLAGS) logcat -v time | grep -iE "webcam|AndroidRuntime|CameraService|camera2|FATAL"

deploy-gil:
	rsync -az --delete --exclude .git --exclude build --exclude '*.keystore' ./ $(REMOTE):$(REMOTE_DIR)/
	ssh $(REMOTE) 'cd $(REMOTE_DIR) && make apk install run ADB_FLAGS="$(ADB_FLAGS)"'

# ------------------------------------------------------------------ phone provisioning / unattended running (all via adb)
SH = $(ADB) $(ADB_FLAGS) shell

# Each step may be refused by some Android versions or makers (shown, not fatal: the leading '-').
provision:
	@echo "== provisioning $(APP_ID) on the attached phone"
	-$(SH) pm grant $(APP_ID) android.permission.CAMERA
	-$(SH) pm grant $(APP_ID) android.permission.RECORD_AUDIO
	-$(SH) pm grant $(APP_ID) android.permission.POST_NOTIFICATIONS
	-$(SH) dumpsys deviceidle whitelist +$(APP_ID)
	-$(SH) cmd appops set $(APP_ID) RUN_ANY_IN_BACKGROUND allow
	-$(SH) cmd appops set $(APP_ID) RUN_IN_BACKGROUND allow
	-$(SH) am set-standby-bucket $(APP_ID) active
	$(if $(OVERLAY),-$(SH) cmd appops set $(APP_ID) SYSTEM_ALERT_WINDOW allow)
	$(if $(STAY_AWAKE),-$(SH) settings put global stay_on_while_plugged_in 3)
	@echo "== done. Vendor battery managers (Xiaomi/Huawei/Oppo/Samsung) may still need 'unrestricted'/auto-start set by hand: see docs/ANDROID.md"

provision-status:
	@echo "-- runtime permissions"; $(SH) "dumpsys package $(APP_ID) | grep -E 'android.permission.(CAMERA|RECORD_AUDIO|POST_NOTIFICATIONS): granted'" || true
	@echo "-- battery whitelist"; $(SH) "dumpsys deviceidle whitelist | grep $(APP_ID)" || echo "(not whitelisted)"
	@echo "-- appops"; $(SH) "cmd appops get $(APP_ID) RUN_ANY_IN_BACKGROUND" || true
	@echo "-- standby bucket"; $(SH) "am get-standby-bucket $(APP_ID)" || true
	@echo "-- camera service"; $(SH) "dumpsys activity services $(APP_ID)/.CameraService | grep -E 'ServiceRecord|isForeground|foregroundServiceType' | head -5" || echo "(service not running)"

phone-info:
	@echo "-- device"; $(SH) "getprop ro.product.manufacturer; getprop ro.product.model; getprop ro.build.version.release; getprop ro.build.version.sdk"
	@echo "-- lock / screen state"; $(SH) "dumpsys window | grep -E 'isKeyguardShowing|mDreamingLockscreen|mAwake|screenState' | head -6" || true
	@echo "-- lock method (empty = no PIN)"; $(SH) "locksettings get-disabled" || true
	@echo "-- cameras and who is using them"; $(SH) "dumpsys media.camera | grep -E 'Device [0-9]|Facing|Orientation|Client|Camera ID' | head -30" || true
	@echo "-- app"; $(SH) "dumpsys package $(APP_ID) | grep -E 'versionName|versionCode' | head -3" || echo "(not installed)"
	$(MAKE) --no-print-directory provision-status

dev: install provision run

lock:
	$(SH) input keyevent KEYCODE_SLEEP

wake:
	$(SH) input keyevent KEYCODE_WAKEUP

kill:
	@echo "kill the app's background process (like the system reclaiming memory); the foreground service should come back or be restarted by 'make watch'"
	$(SH) am kill $(APP_ID)

shot:
	mkdir -p $(BUILD)
	$(ADB) $(ADB_FLAGS) exec-out screencap -p > $(BUILD)/screen.png
	@echo "saved $(BUILD)/screen.png"

wifi-adb:
	@test -n "$(PHONE_IP)" || { echo "usage: make wifi-adb PHONE_IP=192.168.x.y   (phone attached by USB once)"; exit 1; }
	$(ADB) $(ADB_FLAGS) tcpip 5555
	$(ADB) connect $(PHONE_IP):5555

# Babysit the phone from here: if the camera service is not a foreground service, wake the screen and start the app
# (an Activity on screen is what allows Android 12+ to open the camera again). Leave it running in a terminal.
watch:
	@echo "watching $(APP_ID) every $(WATCH_SECS)s (Ctrl-C to stop)"
	@while true; do \
	  if $(ADB) $(ADB_FLAGS) shell dumpsys activity services $(APP_ID)/.CameraService 2>/dev/null | grep -q "isForeground=true"; then :; \
	  else echo "$$(date +%T) camera service not in the foreground - waking the phone and starting the app"; \
	    $(ADB) $(ADB_FLAGS) shell input keyevent KEYCODE_WAKEUP; $(ADB) $(ADB_FLAGS) shell am start -n $(APP_ID)/.MainActivity >/dev/null; fi; \
	  sleep $(WATCH_SECS); done

# ------------------------------------------------------------------ tests / checks
# Compile-check the whole app against the framework API: android.jar from the SDK when present, otherwise the
# android-all jar from Maven Central (a full set of framework classes, ~140 MB, downloaded once).
check: libs
	@if [ -f "$(ANDROID_JAR)" ]; then FW=$(ANDROID_JAR); else $(MAKE) --no-print-directory $(STUBS); FW=$(STUBS); fi; \
	rm -rf $(BUILD)/check && mkdir -p $(BUILD)/check && $(JAVAC) -d $(BUILD)/check -cp $$FW:$(OKHTTP):$(OKIO) $(JAVA_SRC) && echo "compile check OK"

# The real protocol code (core/*) against the real Python hub, with a fake camera. Needs only a JDK, python3 and `pip install websockets cryptography`.
test-protocol: libs
	rm -rf $(BUILD)/test && mkdir -p $(BUILD)/test
	$(JAVAC) -d $(BUILD)/test -cp $(OKHTTP):$(OKIO):$(JSONJAR) $(CORE_SRC) android/test/Harness.java android/test/UnitTests.java
	java -cp $(BUILD)/test:$(OKHTTP):$(OKIO):$(JSONJAR) UnitTests
	python3 android/test/driver.py $(BUILD)/test $(OKHTTP):$(OKIO):$(JSONJAR)

clean:
	rm -rf $(BUILD)
