# Backend Contract (Draft v1)

This document defines the boundary between the Android client and the backend. It deliberately avoids coupling the app to backend source paths.

## Client configuration

- `contract_version`: integer, currently `1`
- `repository_url`: HTTPS GitHub repository URL
- `data_branch`: backend-published data branch
- `client_version`: Android app version

## Contract rules

1. The client must reject unsupported major contract versions.
2. The backend remains the authority for candidate queues, receipts and published subscriptions.
3. The client must not infer success from a local upload attempt; it must verify the remote result.
4. Credentials are never embedded in contract documents or committed files.
5. Any schema change must be documented and tested before the client depends on it.

## Initial integration status

No live backend API is called by this skeleton. Exact read/write paths and receipt semantics will be documented only after reviewing the current backend implementation and agreeing on a stable interface.
