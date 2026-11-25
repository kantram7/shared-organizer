Cloudflare push sender retired in 0.3.6

Production handover: 2026-11-25T04:07:00Z. Firebase Gen2 Functions now process original message/revision events; see ../functions/README.md.

cloudflare.config.ts deliberately points to retired-worker.mjs, with no cron and no credential bindings. HTTP returns health or 410; scheduled callbacks perform no work. Ordinary deploy must never restore the old sender. setup-account.cjs is disabled so it cannot recreate a retired signing key.

worker.mjs and LEGACY-README.txt are retained only as historical migration/test evidence, not an active deployment recipe. The dedicated Worker key and its three secret bindings were revoked/removed after draining pre-boundary items. Firebase Functions use a separate keyless runtime identity.

Old APKs may still atomically write pushQueue. Rules continue accepting those compatible writes; createdAt TTL removes the redundant queue documents without FCM. TTL deletes only queue records, not organizer data, and is asynchronous (typically within 24 hours, billed Firestore deletes). No manual server queue polling remains.
