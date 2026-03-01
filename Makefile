GRADLEW := ./gradlew

# ABI splits produce multiple APKs; reference the universal one for install.
APK_DEBUG_DIR   := app/build/outputs/apk/debug
APK_RELEASE_DIR := app/build/outputs/apk/release
APK_DEBUG_UNIVERSAL   := $(APK_DEBUG_DIR)/app-universal-debug.apk
APK_RELEASE_UNIVERSAL := $(APK_RELEASE_DIR)/app-universal-release.apk

.PHONY: all debug release install lint clean check-env

all: debug

debug:
	$(GRADLEW) assembleDebug
	@echo "Debug APKs → $(APK_DEBUG_DIR)/"

release: check-env
	$(GRADLEW) assembleRelease
	@echo "Release APKs → $(APK_RELEASE_DIR)/"

install: debug
	adb install -r $(APK_DEBUG_UNIVERSAL)

lint:
	$(GRADLEW) lint

clean:
	$(GRADLEW) clean

check-env:
ifndef KEYSTORE_PATH
	$(error KEYSTORE_PATH is not set)
endif
ifndef KEYSTORE_PASSWORD
	$(error KEYSTORE_PASSWORD is not set)
endif
ifndef KEY_ALIAS
	$(error KEY_ALIAS is not set)
endif
ifndef KEY_PASSWORD
	$(error KEY_PASSWORD is not set)
endif
