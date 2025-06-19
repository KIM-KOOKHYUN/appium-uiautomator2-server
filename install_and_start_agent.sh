adb -s $1 uninstall io.appium.uiautomator2.server
adb -s $1 install  app/build/outputs/apk/androidTest/server/debug/appium-uiautomator2-server-debug-androidTest.apk
adb -s $1 install app/build/outputs/apk/server/debug/appium-uiautomator2-server-v7.6.2.apk
adb -s $1 shell am instrument -w io.appium.uiautomator2.server.test/androidx.test.runner.AndroidJUnitRunner


