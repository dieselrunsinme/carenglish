# APK

`bureung-english-release.apk`: Pleos Connect 에뮬레이터 v2.1.0(2560×1440 · 160dpi)에 바로 설치할 수 있는 release 빌드입니다. 바로 설치되도록 디버그 키로 서명했습니다.

```bash
adb install -r bureung-english-release.apk
```

## 심사용 Gemini API 키

이 APK에는 심사용 Gemini API 키가 들어 있어, 설치하면 바로 AI 대화를 해 볼 수 있습니다.

- 키에는 **월 3만 원의 지출 한도**를 걸어 두었습니다. 한도를 넘으면 AI 대화 대신 녹음 수업으로 자동 전환됩니다(앱이 멈추지 않습니다).
- 이 키는 심사가 끝나면 폐기합니다. 그 뒤에는 저장소 루트의 `local.properties`에 본인 키를 넣고 다시 빌드하면 됩니다(루트 README의 "켜는 법").
- 소스 코드에는 키가 없습니다. 키는 빌드할 때 `local.properties`에서만 읽습니다.

다시 만들려면 저장소 루트에서 `./gradlew assembleRelease`를 실행하고,
`app/build/outputs/apk/release/app-release.apk`를 이 폴더에 `bureung-english-release.apk`로 복사합니다.
