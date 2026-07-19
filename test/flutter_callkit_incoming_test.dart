import 'package:flutter/services.dart';
import 'package:flutter_callkit_incoming/entities/call_event.dart';
import 'package:flutter_callkit_incoming/entities/call_kit_params.dart';
import 'package:flutter_callkit_incoming/flutter_callkit_incoming.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  const MethodChannel channel = MethodChannel('flutter_callkit_incoming');
  final messenger =
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;

  late MethodCall invocation;

  setUp(() {
    messenger.setMockMethodCallHandler(channel, (methodCall) async {
      invocation = methodCall;
      return true;
    });
  });

  tearDown(() {
    messenger.setMockMethodCallHandler(channel, null);
  });

  test('typed event decoding preserves complete payloads', () {
    final event = FlutterCallkitIncoming.decodeCallEvent({
      'event': CallEventConstants.actionCallTimeout,
      'body': {
        'id': 'incoming-call-42',
        'accepted': true,
        'number': 'Jane',
        'duration': 45000,
        'extra': {'callId': 'external-call-73'},
      },
    });

    expect(event, isA<CallEventActionCallTimeout>());
    final timeout = event! as CallEventActionCallTimeout;
    expect(timeout.callKitParams.id, 'incoming-call-42');
    expect(timeout.callKitParams.isAccepted, isTrue);
    expect(timeout.callKitParams.handle, 'Jane');
    expect(timeout.callKitParams.duration, 45000);
    expect(timeout.callKitParams.extra, {'callId': 'external-call-73'});
  });

  test('VoIP token and both audio-session keys are decoded', () {
    final token = FlutterCallkitIncoming.decodeCallEvent({
      'event': CallEventConstants.actionDidUpdateDevicePushTokenVoip,
      'body': {'deviceTokenVoIP': 'abc123'},
    });
    final audio = FlutterCallkitIncoming.decodeCallEvent({
      'event': CallEventConstants.actionCallToggleAudioSession,
      'body': {'isActivate': true},
    });
    final canonicalAudio = FlutterCallkitIncoming.decodeCallEvent({
      'event': CallEventConstants.actionCallToggleAudioSession,
      'body': {'isActive': true},
    });

    expect(
      (token! as CallEventActionDidUpdateDevicePushTokenVoip).deviceTokenVoIP,
      'abc123',
    );
    expect((audio! as CallEventActionCallToggleAudioSession).isActive, isTrue);
    expect(
      (canonicalAudio! as CallEventActionCallToggleAudioSession).isActive,
      isTrue,
    );
  });

  test('accept and dismiss forward the complete payload', () async {
    const call = CallKitParams(
      id: 'incoming-call-42',
      extra: {'callId': 'external-call-73'},
    );

    expect(await FlutterCallkitIncoming.acceptIncomingCall(call), isTrue);
    expect(invocation.method, 'acceptIncomingCall');
    expect((invocation.arguments as Map)['id'], 'incoming-call-42');
    expect((invocation.arguments as Map)['extra'], {
      'callId': 'external-call-73',
    });
    expect(await FlutterCallkitIncoming.dismissIncomingCall(call), isTrue);
    expect(invocation.method, 'dismissIncomingCall');
    expect((invocation.arguments as Map)['id'], 'incoming-call-42');
    expect((invocation.arguments as Map)['extra'], {
      'callId': 'external-call-73',
    });
  });

  test('mark connected is separate from accept', () async {
    await FlutterCallkitIncoming.markCallConnected('incoming-call-42');

    expect(invocation.method, 'markCallConnected');
    expect(invocation.arguments, {'id': 'incoming-call-42'});
  });

  test('audio route request includes external-route policy', () async {
    expect(
      await FlutterCallkitIncoming.setAudioRoute(
        'incoming-call-42',
        CallAudioRoute.speaker,
        preserveExternalRoute: true,
      ),
      isTrue,
    );
    expect(invocation.method, 'setAudioRoute');
    expect(invocation.arguments, {
      'id': 'incoming-call-42',
      'route': 'speaker',
      'preserveExternalRoute': true,
    });
  });
}
