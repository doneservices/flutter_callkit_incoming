# Fork policy

This fork stays on upstream `flutter_callkit_incoming` 3.1.3 and carries only
generic native-call boundary fixes. Application-specific signalling, storage,
retry policy, and UI do not belong here.

## Fork contracts

- Typed events preserve the complete call payload, VoIP token, timeout data,
  audio-session state, and acceptance state.
- `acceptIncomingCall` and `markCallConnected` are separate operations.
- `dismissIncomingCall` removes stale UI without a decline/end event.
- Android tracks notification, full-screen, and Telecom calls consistently and
  creates foreground-service channels before `startForeground`.
- Android's existing background dispatcher waits until Dart is ready before
  delivering events and can restart after process death.
- `setAudioRoute` provides supported earpiece/speaker routing while optionally
  preserving wired and Bluetooth routes.

## Related upstream work

Checked on 2026-07-19. The closest open upstream work is:

- [#848](https://github.com/hiennguyen92/flutter_callkit_incoming/pull/848): foreground-service permission types
- [#849](https://github.com/hiennguyen92/flutter_callkit_incoming/pull/849): connected calls must not be answered twice
- [#853](https://github.com/hiennguyen92/flutter_callkit_incoming/pull/853) and [#836](https://github.com/hiennguyen92/flutter_callkit_incoming/pull/836): iOS audio-session event key
- [#854](https://github.com/hiennguyen92/flutter_callkit_incoming/pull/854): Android callbacks after process start
- [#857](https://github.com/hiennguyen92/flutter_callkit_incoming/pull/857): foreground notification startup

No upstream PR should be opened from this fork without explicit approval.

## Patch queue

The fork history is intentionally split into independently reviewable patches:

- `fix: preserve complete typed call events`
- `fix(android): retain calls and restart background callbacks`
- `fix(android): create notification channel before foreground start`
- `feat: expose call lifecycle and audio controls`
- `fix(android): wait for background callback readiness`
- `fix(android): drop deprecated edge-to-edge window APIs`

Each patch should be proposed, replaced by matching upstream work, or dropped
independently. Application-specific persistence and acknowledgement semantics
must remain outside this package.

## Upstream sync procedure

1. Fetch `hiennguyen92/flutter_callkit_incoming` and create a sync branch from
   the reviewed fork commit currently pinned by the consuming application.
2. Merge the desired upstream tag or commit; never float the app dependency to
   an unreviewed branch.
3. Resolve conflicts by preserving the public contracts above, then run
   `flutter test`, Android plugin compilation, and iOS plugin compilation.
4. Pin the candidate commit in the consuming application and run its
   release-device matrix for audio and video calls on iOS and Android.
5. Merge the sync only after code review and device sign-off, then update the
   application's pinned commit.

The device matrix must cover foreground, background, lock screen, terminated
state, accept, decline, timeout, local/remote end, duplicate events, and
earpiece/speaker/wired/Bluetooth routing.
