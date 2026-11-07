.DEFAULT_GOAL := help

GRADLE := ./gradlew
APP := :app

.PHONY: help verify format format-check debug release package metadata clean

help:
	@printf '%s\n' \
		'make verify       Run format, unit-test, lint and debug APK checks.' \
		'make format       Apply Kotlin and Gradle Kotlin DSL formatting.' \
		'make format-check Check formatting without changing files.' \
		'make debug        Build the default debug-signed APK.' \
		'make release      Build a signed release APK when keystore.properties exists; otherwise unsigned.' \
		'make package      Build debug and release APKs.' \
		'make metadata     Print versionName and versionCode.' \
		'make clean        Remove Gradle build output.'

# Kept identical to .github/workflows/verify.yml.
verify:
	$(GRADLE) $(APP):spotlessCheck $(APP):testDebugUnitTest $(APP):lintDebug $(APP):assembleDebug

format:
	$(GRADLE) $(APP):spotlessApply

format-check:
	$(GRADLE) $(APP):spotlessCheck

debug:
	$(GRADLE) $(APP):assembleDebug

release:
	@if [ -f keystore.properties ]; then \
		printf '%s\n' 'Building signed release APK.'; \
	else \
		printf '%s\n' 'keystore.properties not found; building unsigned release APK.'; \
	fi
	$(GRADLE) $(APP):assembleRelease

package: debug release

metadata:
	$(GRADLE) $(APP):printReleaseMetadata

clean:
	$(GRADLE) clean
