# Android app for the IP Webcam (native counterpart of the PWA: same hub protocol, but runs as a foreground service).
# Plain make + the Android SDK command-line tools; no Gradle. See docs/ANDROID.md.
#
#   make sdk            one-time: download the Android SDK command-line tools + platform/build-tools (needs JDK 17+, internet)
#   make apk            build + sign build/android/webcam-<version>.apk
#   make install        adb install -r -g (grants the camera/mic/notification permissions)   [ADB_FLAGS="-s SERIAL"]
#   make run | stop | logs | uninstall | devices
#   make test-protocol  run the protocol tests: the real Java protocol code against the real Python hub (no SDK/phone needed)
#   make check          compile-check everything against android.jar
#   make deploy-gil     rsync the tree to the build/test machine and build+install there   [REMOTE=printer@gil.home]

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

.PHONY: help apk install run stop uninstall logs devices sdk check test-protocol clean deploy-gil libs sdk-check keystore
.DEFAULT_GOAL := help

help:
	@sed -n '2,11p' Makefile | sed 's/^# \{0,1\}//'
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
