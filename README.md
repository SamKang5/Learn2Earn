
# APP INSTALLATION GUIDE (ANDROID ONLY): DOWNLOAD THE "Learn2Earn_vX.X_release" FILE FROM "...\app\release"

# Learn2Earn

Learn2Earn is a native Android family screen-time app. A parent sets a daily
allowance, chooses which apps are managed, and assigns quizzes that can earn
additional time. A child device enforces the allowance locally, so a brief
Firebase outage does not immediately disable protection.

## Stack

- Kotlin + XML, Android min SDK 29 / target SDK 36 / compile SDK 37
- Firebase Authentication (email/password for parents, anonymous identity for
  child devices) and Realtime Database for pairing, live lock rules, runtime
  summaries, and legacy/offline quiz fallback
- Cloudflare Pages Functions + D1 for secure pairing, private quiz answer keys,
  server-side scoring, idempotent submissions, reward caps, and the reward
  ledger
- No Firebase Storage or Cloud Functions are required. Quizzes used by the
  secure path are text-only, keeping the project on free-tier services.

Manual quizzes, pairing, scoring, and rewards do not call OpenAI. The optional
AI quiz generator requires an OpenAI API key and API credits; leave that
feature unused if the deployment must remain strictly no-cost.

## Implemented flow

1. Parent signs in (or chooses guest mode), creates an 8-character secure code,
   and mirrors it to Firebase for proof-of-possession pairing.
2. Child signs in anonymously, claims the code, and creates its own protected
   child record. A 6-character Firebase-only fallback remains available for
   guest mode or while the D1 service is not deployed.
3. The child lock service samples Usage Access, spends free time before bonus
   time and earned bank time, resets at local midnight, and enforces either
   selected apps or whole-device blocking.
4. Parent-created or AI-generated quizzes can specify a reward. Secure
   submissions send choices only; the D1 service owns the answer key and
   credits the wallet once.
5. When usable time reaches zero, the lock overlay offers an **Open Learn2Earn
   to Earn Time** button. It takes the child to the normal **Earn** tab, where
   they can complete an assigned quiz to earn more time.

## Emergency contacts

The locked child screen exposes a restricted Emergency flow with Police `113`,
Fire and Rescue `114`, and Medical Emergency `115`, plus personal contacts that
the parent approves on that same device. Personal emergency contacts are stored
only in the app's local SharedPreferences repository; they are not uploaded to
Firebase and separate parent-device contact synchronization is not implemented.

## Manual two-device demo check

Use two Android emulators, or one emulator plus one physical Android device.

1. Launch the parent application.
2. Launch the child application.
3. Pair the devices from the parent Devices tab.
4. Parent creates or assigns a normal quiz with a time reward and sets a short
   daily screen-time allowance.
5. Child confirms the quiz appears in the Earn tab.
6. Let the countdown reach zero in a managed app.
7. Confirm the child remains blocked.
8. Tap **Open Learn2Earn to Earn Time** on the lock overlay.
9. Complete the assigned quiz from the Earn tab.
10. Confirm reward time is granted exactly once and the countdown resumes.
11. Restart the child application and confirm the completed quiz cannot grant
    its reward again.
12. Confirm Emergency Contacts is still available from the blocked state.

Troubleshooting: Firebase Authentication must have Email/Password and Anonymous
providers enabled. `users/{parentId}/children/{childId}` uses the authenticated
parent UID and child anonymous UID; stale local pairings should be unpaired and
paired again. Permission-denied errors usually mean `database.rules.json` has not
been manually deployed or the app is signed in as the wrong role. Emulator
network failures can block pairing, rule sync, and quiz-result writes even though
the child remains locally restricted.

## Setup

### Firebase

1. Add `google-services.json` to `app/`.
2. Enable Anonymous and Email/Password providers.
3. Apply `database.rules.json` only after the learning service is deployed.
   The rules require a claimed pairing-code proof and no longer allow a child
   token to write emergency-unlock or ownership fields.

### Learning service

From `ai-backend/`:

```powershell
npm install
npx wrangler d1 migrations apply learn2earn-ai-quota --remote
npm run deploy
npx wrangler deploy --config wrangler.learning-plans.jsonc
```

Set `FIREBASE_PROJECT_ID` in `wrangler.jsonc`. The secure manual-quiz loop
needs no API-key secret. Add `OPENAI_API_KEY`, `SAFETY_SALT`, and the plain-text
`OPENAI_QUIZ_MODEL` variable only if the optional AI generator will be enabled.
Set those same three values on the `learn2earn-learning-plans` Worker before
deploying it. That Worker checks queued child learning plans every 15 minutes,
then auto-assigns generated quizzes or leaves them for parent review according
to the plan. The Android URL is configured in `app/src/main/res/values/strings.xml`.

### Android Studio / emulator

1. Open this folder in Android Studio and sync Gradle.
2. Start an API 29+ emulator (the project was smoke-tested on `Small_Phone`).
3. Run the app, choose Parent or Child, and grant the child Usage Access and
   Display-over-other-apps permissions from the protection card.

### Child protection permissions

Usage Access is required on the child device so `ChildLockService` can identify
the foreground app, spend the active screen-time allowance, and keep the blocked
state active when time is exhausted. It is a special Android settings grant, not
a normal runtime permission; during demo setup, open the child protection card
and allow Learn2Earn from the Usage Access system screen. If Usage Access is not
granted, the child remains in the safe setup/restricted path instead of getting
extra time.

Package visibility is intentionally narrow. Learn2Earn no longer declares
`QUERY_ALL_PACKAGES`; it declares manifest `<queries>` only for launchable apps
used by parent app controls, the phone dialer used by Emergency Contacts, and
the contact picker used when a parent explicitly imports an emergency contact.
The manifest has one focused lint suppression on `PACKAGE_USAGE_STATS` because
Android lint flags the special-access declaration even though the app obtains it
through Settings. Firebase rules in `database.rules.json` still require manual
deployment for secure demo runs.

The debug APK is written to
`app/build/outputs/apk/debug/app-debug.apk`.

## Verification

- Android unit tests cover screen-time accounting and quiz scoring.
- Backend tests cover scoring, answer-key redaction, and invalid answer input.
- Run:

```powershell
./gradlew :app:testDebugUnitTest :app:assembleDebug
cd ai-backend
npm test
npm run typecheck
```

Guest mode intentionally uses the Firebase-only fallback. Secure D1 pairing
and server-scored quizzes require a non-anonymous parent account. The fallback
keeps answer keys and reward authority on the child for demo convenience; do
not treat it as a production security boundary.

## Credits

Created by:

- [Đỗ Ngọc Thiên Bảo (elax)](https://elaxuwu.me/)
- [Phạm Hoàng Khang](https://github.com/SamKang5)

## License

Licensed under **PolyForm Noncommercial License 1.0.0**.

In simple terms, this is **similar** to **CC BY-NC 4.0**:

    Attribution: You must credit me.

    Non-Commercial: You cannot sell this or use it for business.

See [LICENSE](LICENSE) for the full legal text.

Commercial use is not permitted under this license.

All trademarks and logos are the property of the original author and are not licensed under this agreement.
