# ntfy Android App
This is the Android app for [ntfy](https://github.com/binwiederhier/ntfy) ([ntfy.sh](https://ntfy.sh)). It is available 
in the [Play Store](https://play.google.com/store/apps/details?id=io.heckel.ntfy).

# Releases
You can find the app in [F-Droid](https://f-droid.org/packages/io.heckel.ntfy/) or the [Play Store](https://play.google.com/store/apps/details?id=io.heckel.ntfy), 
or as .apk files on the [releases page](https://github.com/binwiederhier/ntfy-android/releases).

# Build

## Building without Firebase (F-Droid flavor)
Without Firebase, you may want to still change the default `app_base_url` in [strings.xml](https://github.com/binwiederhier/ntfy-android/blob/main/app/src/main/res/values/strings.xml)
if you're self-hosting the server. Then run:
```
# To build an unsigned .apk (app/build/outputs/apk/fdroid/*.apk)
./gradlew assembleFdroidRelease

# To build a bundle .aab (app/fdroid/release/*.aab)
./gradlew bundleFdroidRelease
```

## Building with Firebase (FCM, Google Play flavor)
To build your own version with Firebase, you must:
* Create a Firebase/FCM account
* Place your account file at `app/google-services.json` 
* And change `app_base_url` in [strings.xml](https://github.com/binwiederhier/ntfy-android/blob/main/app/src/main/res/values/strings.xml)
* Then run:
```
# To build an unsigned .apk (app/build/outputs/apk/play/*.apk)
./gradlew assemblePlayRelease

# To build a bundle .aab (app/play/release/*.aab)
./gradlew bundlePlayRelease
```

## License
Made with ❤️ by [Philipp C. Heckel](https://heckel.io), distributed under the [Apache License 2.0](LICENSE).

Thank you to these fantastic resources:
* [RecyclerViewKotlin](https://github.com/android/views-widgets-samples/tree/main/RecyclerViewKotlin) (Apache 2.0)
* [Just another Hacker News Android client](https://github.com/manoamaro/another-hacker-news-client) (MIT)
* [Android Room with a View](https://github.com/googlecodelabs/android-room-with-a-view/tree/kotlin) (Apache 2.0)
* [Firebase Messaging Example](https://github.com/firebase/quickstart-android/blob/7147f60451b3eeaaa05fc31208ffb67e2df73c3c/messaging/app/src/main/java/com/google/firebase/quickstart/fcm/kotlin/MyFirebaseMessagingService.kt) (Apache 2.0)
* [Designing a logo with Inkscape](https://www.youtube.com/watch?v=r2Kv61cd2P4)
* [Foreground service](https://robertohuertas.com/2019/06/29/android_foreground_services/)
* [github/gemoji](https://github.com/github/gemoji) (MIT) for as data source for an up-to-date [emoji.json](https://raw.githubusercontent.com/github/gemoji/master/db/emoji.json) file
* [emoji-java](https://github.com/vdurmont/emoji-java) (MIT) has been stripped and inlined to use the emoji.json file
