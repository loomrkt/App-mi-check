import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_svg/flutter_svg.dart';

import 'services/overlay_channel.dart';

void main() {
  WidgetsFlutterBinding.ensureInitialized();
  SystemChrome.setSystemUIOverlayStyle(
    const SystemUiOverlayStyle(
      statusBarColor: Colors.transparent,
      statusBarIconBrightness: Brightness.dark,
      statusBarBrightness: Brightness.light,
      systemNavigationBarColor: _bg,
      systemNavigationBarDividerColor: _bg,
      systemNavigationBarIconBrightness: Brightness.dark,
      systemNavigationBarContrastEnforced: false,
    ),
  );
  WidgetsBinding.instance.addPostFrameCallback((_) {
    SystemChrome.setSystemUIOverlayStyle(
      const SystemUiOverlayStyle(
        statusBarColor: Colors.transparent,
        statusBarIconBrightness: Brightness.dark,
        statusBarBrightness: Brightness.light,
        systemNavigationBarColor: _bg,
        systemNavigationBarDividerColor: _bg,
        systemNavigationBarIconBrightness: Brightness.dark,
        systemNavigationBarContrastEnforced: false,
      ),
    );
  });
  runApp(const MCheckApp());
}

const Color _bg = Color(0xFFF6F5F0);
const Color _surface = Color(0xFFFFFFFF);
const Color _ink = Color(0xFF0D1B14);
const Color _muted = Color(0xFF5D6E64);
const Color _line = Color(0xFFE3E6DF);
const Color _primary = Color(0xFF18976B);
const Color _primaryText = Color(0xFF0E6B4E);
const Color _inkOnPrimary = Color(0xFF062016);
const Color _lime = Color(0xFFB2F093);
const Color _inkOnLime = Color(0xFF0E2117);

const TextStyle _metaStyle = TextStyle(
  fontSize: 10,
  fontWeight: FontWeight.w800,
  letterSpacing: 1.4,
);

class MCheckApp extends StatelessWidget {
  const MCheckApp({super.key});

  @override
  Widget build(BuildContext context) {
    final base = ThemeData(
      useMaterial3: true,
      brightness: Brightness.light,
      colorScheme: ColorScheme.fromSeed(
        seedColor: _primary,
        brightness: Brightness.light,
      ).copyWith(
        primary: _primary,
        onPrimary: _inkOnPrimary,
        surface: _surface,
        onSurface: _ink,
      ),
      scaffoldBackgroundColor: _bg,
    );

    return MaterialApp(
      title: 'm-check',
      debugShowCheckedModeBanner: false,
      theme: base.copyWith(
        textTheme: base.textTheme.apply(
          bodyColor: _ink,
          displayColor: _ink,
        ),
        snackBarTheme: SnackBarThemeData(
          behavior: SnackBarBehavior.floating,
          backgroundColor: _ink,
          contentTextStyle: const TextStyle(color: _surface, fontSize: 13),
          shape: RoundedRectangleBorder(
            borderRadius: BorderRadius.circular(10),
          ),
        ),
      ),
      home: const HomePage(),
    );
  }
}

class HomePage extends StatefulWidget {
  const HomePage({super.key});

  @override
  State<HomePage> createState() => _HomePageState();
}

class _HomePageState extends State<HomePage> with WidgetsBindingObserver {
  OverlayStatus _status = OverlayStatus.empty;
  bool _busy = false;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    _refreshStatus();
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.resumed) {
      _refreshStatus();
    }
  }

  Future<void> _refreshStatus() async {
    final status = await OverlayChannel.status();
    if (!mounted) return;
    setState(() => _status = status);
  }

  void _snack(String message) {
    if (!mounted) return;
    ScaffoldMessenger.of(context)
      ..hideCurrentSnackBar()
      ..showSnackBar(SnackBar(content: Text(message)));
  }

  Future<void> _enableOverlayPermission() async {
    await OverlayChannel.requestOverlayPermission();
    _snack('Activez « Afficher par-dessus les autres applications ».');
  }

  Future<void> _startScanMode() async {
    if (_busy) return;
    setState(() => _busy = true);
    try {
      if (!_status.overlayPermission) {
        await _enableOverlayPermission();
        return;
      }
      final started = await OverlayChannel.startOverlay();
      await _refreshStatus();
      _snack(
        started
            ? 'Bouton flottant activé. Touchez-le pour scanner.'
            : 'Capture d\'écran refusée.',
      );
    } on PlatformException catch (error) {
      if (error.code == 'overlay_permission') {
        await _enableOverlayPermission();
      } else {
        _snack(error.message ?? 'Une erreur est survenue.');
      }
    } finally {
      if (mounted) setState(() => _busy = false);
      await _refreshStatus();
    }
  }

  Future<void> _stopScanMode() async {
    await OverlayChannel.stopOverlay();
    await _refreshStatus();
    _snack('Bouton flottant désactivé.');
  }

  @override
  Widget build(BuildContext context) {
    final running = _status.overlayRunning;

    return Scaffold(
      body: SafeArea(
        child: RefreshIndicator(
          onRefresh: _refreshStatus,
          color: _primary,
          backgroundColor: _surface,
          child: ListView(
            padding: const EdgeInsets.fromLTRB(22, 18, 22, 40),
            children: [
              _buildHeader(),
              const SizedBox(height: 30),
              Align(
                alignment: Alignment.centerLeft,
                child: Container(width: 40, height: 4, color: _lime),
              ),
              const SizedBox(height: 18),
              Text.rich(
                TextSpan(
                  children: [
                    const TextSpan(text: 'Scanne un écran.\nSans quitter '),
                    TextSpan(
                      text: 'ton app.',
                      style: const TextStyle(color: _primaryText),
                    ),
                  ],
                ),
                style: const TextStyle(
                  fontSize: 30,
                  height: 1.12,
                  fontWeight: FontWeight.w700,
                  letterSpacing: -1,
                ),
              ),
              const SizedBox(height: 12),
              const Text(
                'Un bouton flottant reste par-dessus tes applications. '
                'Un appui, et m-check lit le texte affiché.',
                style: TextStyle(color: _muted, height: 1.55, fontSize: 13.5),
              ),
              const SizedBox(height: 26),
              _buildSetupCard(running),
              const SizedBox(height: 14),
              SizedBox(
                width: double.infinity,
                height: 54,
                child: Opacity(
                  opacity: _busy ? 0.5 : 1,
                  child: Material(
                    color: Colors.transparent,
                    child: InkWell(
                      onTap: _busy
                          ? null
                          : running
                              ? _stopScanMode
                              : _startScanMode,
                      borderRadius: BorderRadius.circular(12),
                      child: Ink(
                        decoration: BoxDecoration(
                          color: running ? _ink : null,
                          gradient: running
                              ? null
                              : const LinearGradient(
                                  begin: Alignment.topRight,
                                  end: Alignment.bottomLeft,
                                  colors: [
                                    Color(0xFFB2F093),
                                    Color(0xFF57B43E),
                                    Color(0xFF18976B),
                                  ],
                                ),
                          borderRadius: BorderRadius.circular(12),
                        ),
                        child: Center(
                          child: Text(
                            running
                                ? 'Désactiver le bouton flottant'
                                : 'Activer le bouton flottant',
                            style: TextStyle(
                              fontSize: 15,
                              fontWeight: FontWeight.w700,
                              letterSpacing: 0.2,
                              color: running ? _lime : Colors.white,
                            ),
                          ),
                        ),
                      ),
                    ),
                  ),
                ),
              ),
              if (!running)
                const Padding(
                  padding: EdgeInsets.only(top: 10),
                  child: Text(
                    'Android demandera l\'autorisation de capture d\'écran.',
                    style: TextStyle(color: _muted, fontSize: 11.5),
                  ),
                ),
              const SizedBox(height: 26),
              _buildTip(),
            ],
          ),
        ),
      ),
    );
  }

  Widget _buildHeader() {
    return Row(
      children: [
        ExcludeSemantics(
          child: SvgPicture.asset(
            'assets/brand/mark.svg',
            width: 34,
            height: 35,
          ),
        ),
        const SizedBox(width: 11),
        const Expanded(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(
                'm-check',
                maxLines: 1,
                overflow: TextOverflow.ellipsis,
                style: TextStyle(
                  fontSize: 23,
                  fontWeight: FontWeight.w800,
                  letterSpacing: -0.8,
                ),
              ),
              Text(
                'Détecteur de fake news',
                maxLines: 2,
                overflow: TextOverflow.ellipsis,
                style: TextStyle(color: _muted, fontSize: 11.5),
              ),
            ],
          ),
        ),
        const SizedBox(width: 8),
        _StatusPill(active: _status.overlayRunning),
      ],
    );
  }

  Widget _buildSetupCard(bool running) {
    return Material(
      color: _surface,
      shape: RoundedRectangleBorder(
        borderRadius: BorderRadius.circular(16),
        side: const BorderSide(color: _line),
      ),
      child: Padding(
        padding: const EdgeInsets.fromLTRB(18, 16, 18, 4),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            const Text(
              'MISE EN ROUTE',
              style: TextStyle(
                color: _muted,
                fontSize: 10,
                fontWeight: FontWeight.w800,
                letterSpacing: 1.4,
              ),
            ),
            const SizedBox(height: 4),
            _StepRow(
              index: '01',
              title: 'Superposition',
              subtitle: 'Afficher m-check par-dessus les autres apps',
              state: _status.overlayPermission ? 'FAIT' : 'AUTORISER',
              highlight: _status.overlayPermission,
              action: !_status.overlayPermission,
              onTap:
                  _status.overlayPermission ? null : _enableOverlayPermission,
              semanticsLabel: 'Autoriser la superposition',
            ),
            const Divider(height: 1, thickness: 1, color: _line),
            _StepRow(
              index: '02',
              title: 'Capture d\'écran',
              subtitle: 'Nécessaire pour lire le contenu de l\'écran',
              state: running ? 'ACTIF' : 'EN ATTENTE',
              highlight: running,
            ),
          ],
        ),
      ),
    );
  }

  Widget _buildTip() {
    return Container(
      width: double.infinity,
      padding: const EdgeInsets.all(18),
      decoration: BoxDecoration(
        color: _lime,
        borderRadius: BorderRadius.circular(16),
      ),
      child: const Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(
            'ASTUCE',
            style: TextStyle(
              color: _inkOnLime,
              fontSize: 10,
              fontWeight: FontWeight.w800,
              letterSpacing: 1.4,
            ),
          ),
          SizedBox(height: 8),
          Text(
            'Déplace le bouton où tu veux. Un appui long le ferme.',
            style: TextStyle(
              color: _inkOnLime,
              fontSize: 12.5,
              height: 1.5,
              fontWeight: FontWeight.w500,
            ),
          ),
        ],
      ),
    );
  }
}

class _StatusPill extends StatelessWidget {
  const _StatusPill({required this.active});

  final bool active;

  @override
  Widget build(BuildContext context) {
    final color = active ? _inkOnLime : _muted;
    return Semantics(
      container: true,
      label: active ? 'Mode scan actif' : 'Mode scan inactif',
      child: ExcludeSemantics(
        child: Container(
          padding: const EdgeInsets.symmetric(horizontal: 9, vertical: 6),
          decoration: BoxDecoration(
            color: active ? _lime : Colors.transparent,
            borderRadius: BorderRadius.circular(4),
            border: Border.all(color: active ? _lime : _line),
          ),
          child: Row(
            mainAxisSize: MainAxisSize.min,
            children: [
              Container(
                width: 6,
                height: 6,
                color: active ? _primaryText : const Color(0xFFA7B0A8),
              ),
              const SizedBox(width: 6),
              Text(
                active ? 'ACTIF' : 'INACTIF',
                style: TextStyle(
                  fontSize: 9,
                  fontWeight: FontWeight.w800,
                  letterSpacing: 1,
                  color: color,
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}

class _StepRow extends StatelessWidget {
  const _StepRow({
    required this.index,
    required this.title,
    required this.subtitle,
    required this.state,
    this.highlight = false,
    this.action = false,
    this.onTap,
    this.semanticsLabel,
  });

  final String index;
  final String title;
  final String subtitle;
  final String state;
  final bool highlight;
  final bool action;
  final VoidCallback? onTap;
  final String? semanticsLabel;

  @override
  Widget build(BuildContext context) {
    final content = Padding(
      padding: const EdgeInsets.symmetric(vertical: 14),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          SizedBox(
            width: 34,
            child: Padding(
              padding: const EdgeInsets.only(top: 3),
              child: Text(
                index,
                style: _metaStyle.copyWith(color: _muted),
              ),
            ),
          ),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  title,
                  style: const TextStyle(
                    fontSize: 14.5,
                    fontWeight: FontWeight.w600,
                  ),
                ),
                const SizedBox(height: 3),
                Text(
                  subtitle,
                  style: const TextStyle(
                    color: _muted,
                    fontSize: 12.5,
                    height: 1.4,
                  ),
                ),
              ],
            ),
          ),
          const SizedBox(width: 8),
          Padding(
            padding: const EdgeInsets.only(top: 4),
            child: Text(
              state,
              style: TextStyle(
                fontSize: 9.5,
                fontWeight: FontWeight.w800,
                letterSpacing: 1,
                color: action
                    ? _ink
                    : highlight
                        ? _primaryText
                        : _muted,
                decoration: action ? TextDecoration.underline : null,
                decorationColor: _primary,
                decorationThickness: 1.5,
              ),
            ),
          ),
        ],
      ),
    );

    if (onTap == null) return content;

    return Semantics(
      button: true,
      label: semanticsLabel,
      child: InkWell(
        onTap: onTap,
        borderRadius: BorderRadius.circular(8),
        child: content,
      ),
    );
  }
}
