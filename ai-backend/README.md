# Learn2Earn learning service

Cloudflare Pages Functions keep OpenAI credentials and quiz answer keys off
Android devices. D1 stores the private learning model; Firebase ID tokens are
verified at the edge.

## One-time setup

1. In Firebase Console, enable Anonymous and Email/Password authentication.
2. Create a D1 database and put its ID in `wrangler.jsonc`.
3. If the optional AI generator will be enabled, add its Pages secrets:

   ```powershell
   npx wrangler pages secret put OPENAI_API_KEY --project-name learn2earn-ai
   npx wrangler pages secret put SAFETY_SALT --project-name learn2earn-ai
   ```

   Wrangler config sets `OPENAI_QUIZ_MODEL=gpt-5.6-luna` with low reasoning.
   The pairing, manual-quiz, scoring, and reward API does not need these AI
   settings.
4. In Cloudflare Pages **Settings → Variables and Secrets**, add the plain-text
   variable `FIREBASE_PROJECT_ID=learn2earn-bc2bc` for Production (and Preview
   if you deploy preview branches). Do not duplicate this variable in
   `wrangler.jsonc`.
5. Apply every migration, including the hardening migration:

   ```powershell
   npx wrangler d1 migrations apply learn2earn-ai-quota --remote
   ```

6. Deploy:

   ```powershell
   npm install
   npm run deploy
   ```

Run `npm test` and `npm run typecheck` before deploying. Keep `.dev.vars`
local; it is ignored by git.

## Learning API

All requests use `Authorization: Bearer <Firebase ID token>`.

Parent actions (non-anonymous parent account):

- `POST { "action": "createPairingCode", "timezoneOffsetMinutes": 420 }`
- `POST { "action": "setRewardPolicy", "childUid": "...", "dailyEarnedCapMinutes": 120, "timezoneOffsetMinutes": 420 }`
- `POST { "action": "assignQuiz", "childUid": "...", "quiz": { ... }, "minimumScorePercent": 80, "rewardMinutes": 15, "maxAttempts": 2 }`
- `POST { "action": "unpairChild", "childUid": "..." }`
- `GET /api/learning?view=childSummary&childUid=...`

Child actions:

- `POST { "action": "claimPairingCode", "code": "..." }`
- `GET /api/learning?view=nextQuiz`
- `GET /api/learning?view=balance` (cumulative server-awarded minutes)
- `POST { "action": "submitQuiz", "assignmentId": "...", "submissionId": "...", "answers": [[0], [1, 2]] }`

The server strips answer keys from `nextQuiz`, validates/scorers submissions,
uses the client submission ID for retry idempotency, and caps daily rewards
using the family's timezone offset. Unpairing removes assignments, attempts,
wallet state, and the family link.

The Android client calls this API whenever secure D1 pairing succeeds. Guest
mode or an undeployed service falls back to the six-character Firebase flow;
that fallback exposes answer keys to the paired child and awards time locally,
so it should be treated only as a development/demo mode.

The design uses Firebase Auth + Realtime Database and one Cloudflare D1
database. It does not require Firebase Storage, Firebase Cloud Functions,
service-account keys, queues, or paid read replicas. Check the current Firebase
and Cloudflare quota pages before a public launch.

The learning API itself does not call OpenAI. `/api/generate-quiz` is optional
and requires OpenAI API credits plus `OPENAI_API_KEY`, `SAFETY_SALT`, and
`OPENAI_QUIZ_MODEL`; manual quiz creation keeps the main app loop independent
of that cost.
