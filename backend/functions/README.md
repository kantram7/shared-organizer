# Backend 0.3.6

Node 22, Cloud Functions second generation, region `europe-west3`. This source does not deploy itself. Run unit and Firestore emulator tests before deployment.

## Android contract

Use Firebase Functions callable SDK in `europe-west3`; the current Firebase ID token is verified automatically. All methods validate live membership, canonical role, delegate expiration and the current chat parent. Anonymous registration alone grants no access.

`prepareAttachments({spaceId,chatId,messageId,files})`

* Stable random message ID and attachment IDs, `[A-Za-z0-9_-]`, at most 200 characters.
* `files`: 1–10 `{id,name,mime,size,thumbnailSize?,thumbnailMime?}`. Original bytes are unchanged. Maximum 100 MiB per original and 500 MiB per message. Thumbnail is optional, at most 512 KiB, image/jpeg, image/png or image/webp. The original MIME describes the file, not a trusted assertion that it is safe to execute.
* Returns `{attachments:[{id,storagePath,uploadUrl,headers,uploadExpiresAtMillis,expiresAtMillis,thumbnailUploadUrl?,thumbnailHeaders?}],maxFileBytes,maxMessageBytes}`.
* PUT original bytes to `uploadUrl`, with **every returned header** (including exact Content-Length and `x-goog-if-generation-match: 0`). PUT optional generated thumbnail to its URL with its headers. URLs expire after 15 minutes. Retries use the same IDs/files. HTTP 412 means an object already exists; continue to commit, which verifies it. Do not overwrite originals or persist/log signed URLs. A changed file selection needs a new message ID.
* Firestore manifest `spaces/{space}/attachments/{id}` contains immutable identity/name/mime/size/owner/actor/chatId/messageId/storagePath, server timestamps `createdAt` (preparation), `sentAt` (successful send), `expiresAt` (exactly 30 days after `sentAt`), and server-controlled `state:pending|ready|expired`, `cleanupAt`, `generation`, `md5Hash`, optional thumbnail fields. The prepare response expiry is provisional for backward compatibility; the ready manifest/commit result is authoritative. Client reads use the normal parent privacy guard; all client manifest writes are denied.

`commitAttachmentMessage({spaceId,chatId,messageId,text,format?,replyTo?,attachmentIds})`

* After all PUTs complete. Validates the actual bucket object size/type, ownership, live chat, matching message and total bytes. Writes the message and marks manifests ready atomically, using one trusted server send timestamp for message `createdAt` and manifest `sentAt`. Expiry/cleanup are exactly 30 days later. Retry is idempotent and cannot extend this window. Result `{messageId,alreadySent,expiresAtMillis}`; update the sender's cached metadata with this final expiry. Drafts abandoned over 24 hours cannot be revived by committing late.
* `text` is optional content expressed as an empty string when absent, max 4000 characters; `format` defaults to `plain-v1` and also accepts `markdown-v1`. `replyTo` is a message ID in the same chat, or empty.
* Ordinary text messages still use Firestore directly. Direct client creation with nonempty `attachmentIds` is denied, so clients cannot bypass object validation.

`attachmentDownload({spaceId,attachmentId,thumbnail?:boolean})`

* Validates current membership, parent, nondeleted message and 30-day expiration. Returns `{url,expiresAtMillis,size,mime,md5Hash}`; GET the URL within 60 seconds. Preserve original bytes in the user-selected phone folder. A URL is a short-lived bearer capability: authorization revocation can leave at most this one-minute issued URL window. Never store a permanent download token or URL in messages. `not-found` after expiration is an expected state; use the phone copy if present.
* Thumbnail download is optional; generated thumbnails never replace originals.

Message additions: optional `format`, `replyTo`, `attachmentIds`, `editedAt` server timestamp. Existing plain messages remain supported. Per-message receipts are `.../messages/{message}/reads/{canonicalUid}` with `{readAt:serverTimestamp,actor:authUid}`. Reactions are `.../messages/{message}/reactions/{canonicalUid}` with `{emoji,updatedAt:serverTimestamp,actor:authUid}`; one of 💜 👍 🐾 😊. Only one's own canonical document can be written; delegates must use their canonical role's document. Removing a reaction deletes that document. No content preview is copied into replies, so deleting the source cannot leave private quoted text behind.

Notification history: `spaces/{space}/notifications/{canonicalUid}/items/{eventId}`. Server creates neutral `kind,title,body,recordId,chatId,messageId,collectionId,itemId,spaceId,createdAt,read,expiresAt`, plus `authorUid`, `authorRole`, `targetRevision` references. The owner may only set `read` to true; no device token, display name, event title or message content is copied into history. Android resolves the current author name/title/message preview from presently authorized source state, clearing derived content on deletion or permission failure and falling back to the neutral history text. This avoids retaining a disclosed title in the history document after source access is revoked. Use a `where('expiresAt','>',now)` query. Receipts, not history read flags, suppress chat pushes.

For relevant shared records, FCM title includes the current author's display name and plain record title after live recipient/source permission checks. Markdown link targets and formatting are omitted. Details/notes are never included. BUSY/private records remain silent; chat pushes remain neutral. Already-delivered OS notifications cannot retroactively be made secret, so this enrichment is limited to recipients with full record access at send time.

## Push cutover and delivery

`system/pushDelivery` is server-only `{enabled:boolean,cutoverAt:timestamp}`. With no document, disabled flag or missing timestamp, functions send nothing. Original message creation and record revision creation are the only push triggers, preserving old APK writes without also consuming `pushQueue`.

An isolated delivery cohort may additionally use `{testActorUids:[exactAuthUid,...],testDeviceAuthUids:[exactAuthUid,...],testCutoverAt:timestamp}` with production `enabled:false`. Only sources from those actual actors route to the exact listed authenticated device owners; canonical role alone never qualifies a real phone as a test device. This does not grant any data access: normal membership/delegate expiry checks still apply. Test records are in the explicitly authorized workspace and their neutral history can be visible to its canonical users. Test APKs must not also write legacy `pushQueue`, to prevent the old sender from duplicating them.

After tests and explicit deployment authorization, choose a future server cutover timestamp T. Stage and verify the legacy Worker with FUNCTIONS_CUTOVER_AT=T: it handles only immutable source timestamps <T; Functions handles >=T. Enable Functions with cutoverAt=T before that boundary, after the gated Worker is confirmed. Existing queue rules require source and queue to be committed atomically with the same server timestamp, so an offline old APK batch arriving after T belongs to Functions. The Worker acknowledges redundant post-T queue items without sending. Drain all pre-T queue items, then stop the old cron and HTTP delivery paths and allow bounded in-flight work to finish. Missing source timestamps remain deferred for review. Record T, deployed versions and queue disposition privately. Never run overlapping ownership predicates. Legacy queue-write rules remain until old APK support ends.

FCM is data-only. Every device delivery claims a transactional 180-second lease under `deliveryEvents/{stableEventId}/devices/{hashedDeviceId}`; retries skip sent or suppressed devices. Transient errors cause function retry; permanent unregistered tokens are removed only if unchanged. Source privacy, live actor/recipient membership and exact-message receipts are checked immediately before send. A message read while FCM is in flight must also be suppressed by Android using messageId/local receipt. FCM and Firestore cannot be committed in one transaction: a crash after FCM accepts but before the ledger marks sent can repeat delivery; Android must deduplicate stable eventId. No exactly-once promise.

Notifications expire after 24 hours for retry; ledger/history retention is 30 days. Configure Firestore TTL for delivery device `expiresAt`, notification item `expiresAt`, and upload session `expiresAt` using appropriate collection-group field overrides. The ledger contains statuses, not message text or tokens.

## Storage lifecycle and deployment prerequisites

Set `CHAT_ATTACHMENTS_BUCKET` to the dedicated private bucket in Frankfurt. Storage rules deny all direct client reads/writes; callable authorization plus short-lived signed GCS URLs are used because Storage rules allow only two Firestore document lookups, insufficient for membership + space + manifest + live parent checks. The function service account needs Firestore access, FCM send, bucket object access and IAM `signBlob` for its own signing identity. No service-account JSON belongs in an APK or the repository.

**Before uploading any production files**, disable bucket soft delete (`retentionDurationSeconds:0`) and object versioning, remove default Firebase download-token metadata, enable uniform bucket-level access and public-access prevention, and set a lifecycle delete at age 31 days. The application stops download exactly 30 days after successful send; hourly scheduled cleanup deletes expired originals and thumbnails, and uploads abandoned over 24 hours. A send must occur within the 24-hour draft window, so the 31-day object-age fallback cannot shorten the 30-day post-send window. Lifecycle is a safety net for orphaned objects, not the access-control clock. Verify these actual bucket settings in the release audit. Default seven-day soft delete would otherwise retain deleted originals and continue charging for them. Do not enable retention locks. Object finalization removes mismatched/late uploads. Deleting a message immediately purges its files; deleting the parent immediately denies new downloads and normal expiry later removes bytes.

Signing URLs does not make the bucket public. Signed requests bypass Storage rules; the callable validation and content-length/generation signature constraints are therefore part of the security boundary. Client file open uses external safe viewers, not arbitrary HTML inside the app.

Official references: [Firestore triggers](https://firebase.google.com/docs/functions/firestore-events), [retry behavior](https://firebase.google.com/docs/functions/retries), [cross-service rule limit](https://firebase.google.com/docs/rules/rules-behavior), [GCS signed URLs](https://cloud.google.com/storage/docs/access-control/signed-urls), [soft delete](https://cloud.google.com/storage/docs/soft-delete).

## Invocation IAM verification

Deployment runs backend/verify-invokers-036.cjs apply after Firebase CLI completes. It grants roles/run.invoker only on the five exact Eventarc destination services to their existing dedicated trigger service account; no project-wide invoker grant or public event endpoint is allowed. Existing conditional bindings and policy etags are preserved. The read-only deployment audit runs inspect and fails on a missing grant, even if Cloud Functions reports ACTIVE. The three onCall endpoints retain public transport with Firebase identity and live space/chat permissions checked in handlers. Cleanup remains private and its Scheduler OIDC identity is checked. Production push policy is not changed by this verification.



## Read history and cleanup reliability

messageRead observes canonical per-message receipts and marks only matching existing notification history as read. The history-creation transaction also reads the receipt, covering either order of concurrent delivery and reading. Repeated triggers never create history or send FCM. Scheduled attachment cleanup isolates failures per document, finishes other due objects, then requests its bounded retry. Both originals and thumbnails use their recorded generation preconditions so a replacement object cannot be deleted accidentally.

## Completed 0.3.6 production handover

Production cutover was activated at **2026-11-25T04:07:00Z** with a strictly disjoint immutable-source boundary. The old Cloudflare sender was drained, its cron disabled and deployment replaced by an inert entrypoint; all delivery secret bindings and its dedicated signing key were removed. The repository default also deploys only the inert entrypoint. Old APK queue-write rules remain compatible; the redundant pushQueue.createdAt TTL is ACTIVE and removes only queue documents asynchronously. No client upgrade is required for basic delivery/deep links; 0.3.6 adds richer client behavior. Operational proofs, exact versions and key fingerprint stay in ignored local admin reports.
