#!/bin/sh
set -eu

PROJECT="StepHealth.xcodeproj"
SCHEME="StepHealth"
CONFIGURATION="Debug"
DESTINATION="platform=iOS Simulator,name=iPhone 16"

xcodebuild -project "$PROJECT" -scheme "$SCHEME" -configuration "$CONFIGURATION" -destination "$DESTINATION" clean build

echo "iOS simulator build completed."
