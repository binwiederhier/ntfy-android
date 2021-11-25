# ntfy Android App
This is the Android app for [ntfy](https://github.com/binwiederhier/ntfy) ([ntfy.sh](https://ntfy.sh)). It is available 
in the [Play Store](https://play.google.com/store/apps/details?id=io.heckel.ntfy).

# Releases
You can find the app in the [Play Store](https://play.google.com/store/apps/details?id=io.heckel.ntfy), or as .apk files on the [releases page](https://github.com/binwiederhier/ntfy-android/releases).

There is a ticket to create an [F-Droid version](https://github.com/binwiederhier/ntfy/issues/7), but I haven't had the time yet.

# Build instructions
(Todo)

## Building with your own Firebase Cloud Messaging (FCM) account
To build your own version with Firebase, you must:
* Put your own `google-services.json` file in the [app/ folder](https://github.com/binwiederhier/ntfy-android/tree/main/app)
* And change `app_base_url` in [strings.xml](https://github.com/binwiederhier/ntfy-android/blob/main/app/src/main/res/values/strings.xml)

## License
Made with ❤️ by [Philipp C. Heckel](https://heckel.io), distributed under the [Apache License 2.0](LICENSE).

Thank you to these fantastic resources:
* [RecyclerViewKotlin](https://github.com/android/views-widgets-samples/tree/main/RecyclerViewKotlin) (Apache 2.0)
* [Just another Hacker News Android client](https://github.com/manoamaro/another-hacker-news-client) (MIT)
* [Android Room with a View](https://github.com/googlecodelabs/android-room-with-a-view/tree/kotlin) (Apache 2.0)
* [Firebase Messaging Example](https://github.com/firebase/quickstart-android/blob/7147f60451b3eeaaa05fc31208ffb67e2df73c3c/messaging/app/src/main/java/com/google/firebase/quickstart/fcm/kotlin/MyFirebaseMessagingService.kt) (Apache 2.0)
* [Designing a logo with Inkscape](https://www.youtube.com/watch?v=r2Kv61cd2P4)
* [Foreground service](https://robertohuertas.com/2019/06/29/android_foreground_services/)
