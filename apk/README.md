# APK

`bureung-english.apk`: Pleos Connect 에뮬레이터 v2.1.0에 바로 설치할 수 있는 빌드입니다(심사용으로 debug 키로 서명).

```bash
adb install -r bureung-english.apk
```

다시 만들려면 저장소 루트에서 `./gradlew assembleRelease`를 실행하고,
`app/build/outputs/apk/release/app-release.apk`를 이 폴더에 `bureung-english.apk`로 복사합니다.
