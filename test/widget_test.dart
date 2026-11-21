import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';

import 'package:chekeov2/main.dart';

void main() {
  testWidgets('m-check home renders', (WidgetTester tester) async {
    await tester.pumpWidget(const MCheckApp());
    await tester.pump();

    expect(find.text('m-check'), findsOneWidget);
    expect(find.text('Détecteur de fake news'), findsOneWidget);
    expect(find.text('Activer le bouton flottant'), findsOneWidget);
    expect(find.text('DERNIER SCAN'), findsNothing);
  });

  testWidgets('m-check home handles large text scale', (tester) async {
    tester.view.physicalSize = const Size(1080, 2400);
    tester.view.devicePixelRatio = 3;
    tester.platformDispatcher.textScaleFactorTestValue = 1.4;
    addTearDown(() {
      tester.view.resetPhysicalSize();
      tester.view.resetDevicePixelRatio();
      tester.platformDispatcher.clearTextScaleFactorTestValue();
    });

    await tester.pumpWidget(const MCheckApp());
    await tester.pump();

    expect(tester.takeException(), isNull);
    await tester.scrollUntilVisible(
      find.text('ASTUCE'),
      300,
      scrollable: find.byType(Scrollable).first,
    );
    await tester.pump();
    expect(tester.takeException(), isNull);
    expect(find.text('ASTUCE'), findsOneWidget);
  });
}
