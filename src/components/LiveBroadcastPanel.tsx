import { useEffect, useRef, useState } from 'react';
import {
  Cast,
  CheckCircle2,
  Download,
  ExternalLink,
  MonitorPlay,
  Radio,
  Smartphone,
  Square,
  Tv,
  Users,
  Volume2,
  VolumeX,
} from 'lucide-react';

interface NativeAppSource {
  id: string;
  label: string;
  category: string;
  androidPackage?: string;
  webUrl: string;
}

const NATIVE_APP_SOURCES: NativeAppSource[] = [
  {
    id: 'flatv',
    label: 'FlaTV+',
    category: 'Canal Oficial',
    androidPackage: 'com.flamengo.flatv',
    webUrl: 'https://flatvmais.com.br',
  },
  {
    id: 'premiere',
    label: 'Premiere',
    category: 'Brasileirão & Copa',
    androidPackage: 'com.globo.premierefc',
    webUrl: 'https://premiere.globo.com',
  },
  {
    id: 'globoplay',
    label: 'Globoplay',
    category: 'TV Globo / SporTV',
    androidPackage: 'com.globo.globotv',
    webUrl: 'https://globoplay.globo.com',
  },
  {
    id: 'cazetv',
    label: 'CazéTV',
    category: 'Streaming Ao Vivo',
    androidPackage: 'com.google.android.youtube',
    webUrl: 'https://www.youtube.com/@CazeTV',
  },
  {
    id: 'espn',
    label: 'ESPN / Disney+',
    category: 'Libertadores',
    androidPackage: 'com.disney.disneyplus',
    webUrl: 'https://www.disneyplus.com',
  },
  {
    id: 'paramount',
    label: 'Paramount+',
    category: 'Copas',
    androidPackage: 'com.cbs.ca',
    webUrl: 'https://www.paramountplus.com',
  },
  {
    id: 'iptv',
    label: 'Player IPTV / Nativo',
    category: 'App instalado',
    androidPackage: 'com.nst.iptvsmarterstvbox',
    webUrl: 'https://play.google.com/store/apps/details?id=com.nst.iptvsmarterstvbox',
  },
  {
    id: 'custom',
    label: 'Outro App Nativo',
    category: 'Escolher janela/pacote',
    webUrl: 'https://play.google.com/store',
  },
];

function buildMobileAppLaunchHref(source: NativeAppSource, customPkg: string): string {
  const isAndroid = typeof navigator !== 'undefined' && /Android/i.test(navigator.userAgent);
  const rawPkg = source.id === 'custom' ? customPkg.trim() : source.androidPackage || '';
  const safePkg = /^[a-zA-Z0-9_.]+$/.test(rawPkg) ? rawPkg : '';
  if (isAndroid && safePkg) {
    return `intent://#Intent;scheme=https;package=${safePkg};S.browser_fallback_url=${encodeURIComponent(
      source.webUrl
    )};end`;
  }
  return source.webUrl;
}

type QualityPreset = '720p' | '1080p' | '480p';

const QUALITY_CONFIG: Record<QualityPreset, { width: number; height: number; fps: number; jpeg: number; label: string }> = {
  '1080p': { width: 1920, height: 1080, fps: 20, jpeg: 0.72, label: '1080p Full HD' },
  '720p': { width: 1280, height: 720, fps: 24, jpeg: 0.72, label: '720p HD (Recomendado)' },
  '480p': { width: 854, height: 480, fps: 18, jpeg: 0.65, label: '480p Economia' },
};

interface LiveBroadcastPanelProps {
  getAdminToken: () => Promise<string | null>;
  appName: string;
}

export function LiveBroadcastPanel({ getAdminToken, appName }: LiveBroadcastPanelProps) {
  const [selectedSourceId, setSelectedSourceId] = useState<string>('flatv');
  const [customSourceName, setCustomSourceName] = useState('');
  const [customPackageName, setCustomPackageName] = useState('');
  const [broadcastTitle, setBroadcastTitle] = useState(`Flamengo Ao Vivo · ${appName}`);
  const [quality, setQuality] = useState<QualityPreset>('720p');
  const [captureAudio, setCaptureAudio] = useState(true);
  const [autoPublishOnStart, setAutoPublishOnStart] = useState(true);

  const [isLive, setIsLive] = useState(false);
  const [connecting, setConnecting] = useState(false);
  const [viewerCount, setViewerCount] = useState(0);
  const [elapsedSec, setElapsedSec] = useState(0);
  const [errorMsg, setErrorMsg] = useState<string | null>(null);
  const [statusMsg, setStatusMsg] = useState<string | null>(null);
  const [hasCapturedAudio, setHasCapturedAudio] = useState(false);
  const [framesSent, setFramesSent] = useState(0);

  const previewVideoRef = useRef<HTMLVideoElement | null>(null);
  const streamRef = useRef<MediaStream | null>(null);
  const wsRef = useRef<WebSocket | null>(null);
  const frameTimerRef = useRef<number | null>(null);
  const audioCtxRef = useRef<AudioContext | null>(null);
  const processorRef = useRef<ScriptProcessorNode | null>(null);
  const timerIntervalRef = useRef<number | null>(null);
  const sendingFrameRef = useRef(false);

  const activeSourceObj =
    NATIVE_APP_SOURCES.find((s) => s.id === selectedSourceId) || NATIVE_APP_SOURCES[0];
  const selectedSource =
    selectedSourceId === 'custom'
      ? customSourceName.trim() || 'App Nativo Selecionado'
      : activeSourceObj.label;
  const mobileLaunchHref = buildMobileAppLaunchHref(activeSourceObj, customPackageName);
  const supportsWebDisplayCapture =
    typeof navigator !== 'undefined' &&
    Boolean(navigator.mediaDevices && typeof navigator.mediaDevices.getDisplayMedia === 'function');

  function cleanupBroadcastResources(notifyStop: boolean) {
    if (frameTimerRef.current !== null) {
      window.clearInterval(frameTimerRef.current);
      frameTimerRef.current = null;
    }
    if (timerIntervalRef.current !== null) {
      window.clearInterval(timerIntervalRef.current);
      timerIntervalRef.current = null;
    }
    if (processorRef.current) {
      try {
        processorRef.current.disconnect();
      } catch {
        // ignore
      }
      processorRef.current = null;
    }
    if (audioCtxRef.current) {
      void audioCtxRef.current.close().catch(() => undefined);
      audioCtxRef.current = null;
    }
    if (streamRef.current) {
      for (const track of streamRef.current.getTracks()) {
        track.stop();
      }
      streamRef.current = null;
    }
    if (previewVideoRef.current) {
      previewVideoRef.current.srcObject = null;
    }
    if (wsRef.current) {
      try {
        if (notifyStop && wsRef.current.readyState === WebSocket.OPEN) {
          wsRef.current.send(JSON.stringify({ type: 'broadcast:stop' }));
        }
        wsRef.current.close();
      } catch {
        // ignore
      }
      wsRef.current = null;
    }
    sendingFrameRef.current = false;
    setIsLive(false);
    setConnecting(false);
    setHasCapturedAudio(false);
  }

  useEffect(() => {
    return () => {
      cleanupBroadcastResources(true);
    };
  }, []);

  async function startLiveBroadcast() {
    setErrorMsg(null);
    setStatusMsg(null);

    if (!navigator.mediaDevices || typeof navigator.mediaDevices.getDisplayMedia !== 'function') {
      setErrorMsg(
        'Este navegador móvel não suporta captura direta de outros apps via Web. Para transmitir a tela de outros apps no celular Android, use o aplicativo APK Android nativo (pasta /android) ou abra o Painel Admin no Chrome/Edge de um computador.'
      );
      return;
    }

    setConnecting(true);
    const preset = QUALITY_CONFIG[quality];

    try {
      // 1. Pede ao navegador / sistema operacional para escolher o App Nativo / Janela / Guia
      const displayStream = await navigator.mediaDevices.getDisplayMedia({
        video: {
          width: { ideal: preset.width },
          height: { ideal: preset.height },
          frameRate: { ideal: preset.fps, max: 30 },
        },
        audio: captureAudio,
      });

      streamRef.current = displayStream;
      const videoTrack = displayStream.getVideoTracks()[0];
      const audioTracks = displayStream.getAudioTracks();
      const audioActive = audioTracks.length > 0;
      setHasCapturedAudio(audioActive);

      if (previewVideoRef.current) {
        previewVideoRef.current.srcObject = displayStream;
        await previewVideoRef.current.play().catch(() => undefined);
      }

      // Auto-publicação do site e APK ao iniciar transmissão
      const idToken = await getAdminToken();
      if (!idToken) {
        setErrorMsg('Sessão administrativa expirada. Faça login novamente.');
        cleanupBroadcastResources(false);
        return;
      }
      if (autoPublishOnStart) {
        void fetch('/api/admin/auto-publicar', {
          method: 'POST',
          headers: {
            'Content-Type': 'application/json',
            Authorization: `Bearer ${idToken}`,
          },
          body: JSON.stringify({ siteUrl: window.location.origin }),
        }).catch(() => undefined);
      }

      // 2. Abre WebSocket /ws/live e autentica como broadcaster admin
      const proto = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
      const ws = new WebSocket(`${proto}//${window.location.host}/ws/live`);
      ws.binaryType = 'arraybuffer';
      wsRef.current = ws;

      const sampleRate = 24000;

      ws.onopen = () => {
        ws.send(
          JSON.stringify({
            type: 'auth:broadcaster',
            idToken,
            title: broadcastTitle.trim() || `Transmissão Ao Vivo · ${appName}`,
            sourceApp: selectedSource,
            hasAudio: audioActive,
            sampleRate,
          })
        );
      };

      ws.onmessage = (event) => {
        if (typeof event.data !== 'string') return;
        try {
          const msg = JSON.parse(event.data) as {
            type?: string;
            erro?: string;
            state?: { viewerCount?: number; isLive?: boolean };
          };
          if (msg.type === 'auth:error') {
            setErrorMsg(msg.erro || 'Não autorizado para transmitir.');
            cleanupBroadcastResources(false);
            return;
          }
          if (msg.type === 'auth:ok') {
            setIsLive(true);
            setConnecting(false);
            setElapsedSec(0);
            setFramesSent(0);
            setStatusMsg(
              autoPublishOnStart
                ? `Transmitindo "${selectedSource}" ao vivo! Site e APK auto-publicados na área de transmissão.`
                : `Transmitindo "${selectedSource}" ao vivo para os assinantes.`
            );
            timerIntervalRef.current = window.setInterval(() => {
              setElapsedSec((prev) => prev + 1);
            }, 1000);
            startFrameCaptureLoop(ws, preset);
            if (audioActive) {
              startAudioCapture(ws, displayStream, sampleRate);
            }
          }
          if (msg.type === 'live:state' && msg.state) {
            setViewerCount(msg.state.viewerCount ?? 0);
          }
          if (msg.type === 'broadcast:superseded') {
            setErrorMsg(msg.erro || 'Outra sessão assumiu a transmissão.');
            cleanupBroadcastResources(false);
          }
        } catch {
          // ignore
        }
      };

      ws.onerror = () => {
        setErrorMsg('Falha na conexão WebSocket com o servidor de transmissão.');
        cleanupBroadcastResources(false);
      };

      ws.onclose = () => {
        if (streamRef.current) {
          cleanupBroadcastResources(false);
        }
      };

      if (videoTrack) {
        videoTrack.onended = () => {
          setStatusMsg('Compartilhamento do app encerrado pelo usuário.');
          cleanupBroadcastResources(true);
        };
      }
    } catch (err) {
      setConnecting(false);
      cleanupBroadcastResources(false);
      const msg = err instanceof Error ? err.message : '';
      if (msg.toLowerCase().includes('permission') || msg.toLowerCase().includes('denied')) {
        setErrorMsg('Seleção de janela/app cancelada ou permissão negada.');
      } else {
        setErrorMsg(msg || 'Não foi possível iniciar a captura do aplicativo.');
      }
    }
  }

  function startFrameCaptureLoop(
    ws: WebSocket,
    preset: { width: number; height: number; fps: number; jpeg: number }
  ) {
    const canvas = document.createElement('canvas');
    const ctx = canvas.getContext('2d');
    const intervalMs = Math.max(40, Math.round(1000 / preset.fps));

    frameTimerRef.current = window.setInterval(() => {
      const video = previewVideoRef.current;
      if (!video || !ctx || ws.readyState !== WebSocket.OPEN) return;
      if (video.readyState < 2 || video.videoWidth === 0 || video.videoHeight === 0) return;
      if (sendingFrameRef.current || ws.bufferedAmount > 512 * 1024) return;

      const scale = Math.min(1, preset.width / video.videoWidth, preset.height / video.videoHeight);
      const targetW = Math.max(320, Math.round(video.videoWidth * scale));
      const targetH = Math.max(180, Math.round(video.videoHeight * scale));

      if (canvas.width !== targetW || canvas.height !== targetH) {
        canvas.width = targetW;
        canvas.height = targetH;
      }

      ctx.drawImage(video, 0, 0, targetW, targetH);
      sendingFrameRef.current = true;

      canvas.toBlob(
        (blob) => {
          sendingFrameRef.current = false;
          if (!blob || ws.readyState !== WebSocket.OPEN) return;
          blob.arrayBuffer().then((ab) => {
            if (ws.readyState !== WebSocket.OPEN) return;
            const packet = new Uint8Array(1 + ab.byteLength);
            packet[0] = 0x01; // FRAME_VIDEO
            packet.set(new Uint8Array(ab), 1);
            ws.send(packet);
            setFramesSent((c) => c + 1);
          });
        },
        'image/jpeg',
        preset.jpeg
      );
    }, intervalMs);
  }

  function startAudioCapture(ws: WebSocket, mediaStream: MediaStream, targetRate: number) {
    try {
      const AudioCtx = window.AudioContext || (window as unknown as { webkitAudioContext: typeof AudioContext }).webkitAudioContext;
      const audioCtx = new AudioCtx({ sampleRate: targetRate });
      audioCtxRef.current = audioCtx;

      const audioOnlyStream = new MediaStream(mediaStream.getAudioTracks());
      const source = audioCtx.createMediaStreamSource(audioOnlyStream);
      const processor = audioCtx.createScriptProcessor(2048, 1, 1);
      processorRef.current = processor;

      const silentGain = audioCtx.createGain();
      silentGain.gain.value = 0;

      processor.onaudioprocess = (e) => {
        if (ws.readyState !== WebSocket.OPEN || ws.bufferedAmount > 256 * 1024) return;
        const input = e.inputBuffer.getChannelData(0);
        const pcm16 = new Int16Array(input.length);
        for (let i = 0; i < input.length; i += 1) {
          const clamped = Math.max(-1, Math.min(1, input[i]));
          pcm16[i] = clamped < 0 ? clamped * 0x8000 : clamped * 0x7fff;
        }
        const rawBytes = new Uint8Array(pcm16.buffer);
        const packet = new Uint8Array(1 + rawBytes.length);
        packet[0] = 0x02; // FRAME_AUDIO
        packet.set(rawBytes, 1);
        ws.send(packet);
      };

      source.connect(processor);
      processor.connect(silentGain);
      silentGain.connect(audioCtx.destination);
    } catch {
      setHasCapturedAudio(false);
    }
  }

  function formatDuration(sec: number): string {
    const h = Math.floor(sec / 3600);
    const m = Math.floor((sec % 3600) / 60);
    const s = sec % 60;
    const pad = (n: number) => String(n).padStart(2, '0');
    return h > 0 ? `${pad(h)}:${pad(m)}:${pad(s)}` : `${pad(m)}:${pad(s)}`;
  }

  return (
    <section className="rounded-xl border border-zinc-800 bg-zinc-900 p-6 space-y-6">
      <div className="flex flex-col justify-between gap-3 sm:flex-row sm:items-center">
        <div>
          <div className="flex items-center gap-2 text-xs font-semibold text-red-400">
            <Radio className={`h-4 w-4 ${isLive ? 'animate-pulse text-emerald-400' : ''}`} />
            <span>{isLive ? 'TRANSMISSÃO AO VIVO NO AR' : 'Estúdio de Transmissão Admin'}</span>
          </div>
          <h2 className="mt-1 font-display text-2xl font-bold text-white">
            Transmitir Jogo Ao Vivo (Escolher App Nativo)
          </h2>
          <p className="mt-1 text-xs text-zinc-400">
            Clique no app nativo que deseja transmitir, selecione a janela/tela do aplicativo e
            inicie o sinal ao vivo direto para os celulares e Smart TVs autorizadas.
          </p>
        </div>

        {isLive && (
          <div className="flex items-center gap-4 rounded-lg border border-emerald-800 bg-emerald-950/50 px-4 py-2 text-xs">
            <span className="inline-flex items-center gap-1.5 font-semibold text-emerald-300">
              <Users className="h-3.5 w-3.5" />
              {viewerCount} assistindo
            </span>
            <span className="font-mono text-zinc-200 tabular-nums">{formatDuration(elapsedSec)}</span>
          </div>
        )}
      </div>

      {/* Passo 1: Escolher App Nativo */}
      <div className="space-y-2.5">
        <label className="block text-xs font-semibold text-zinc-200">
          1. Escolha qual App Nativo você vai transmitir:
        </label>
        <div className="grid grid-cols-2 gap-2.5 sm:grid-cols-4">
          {NATIVE_APP_SOURCES.map((src) => {
            const active = selectedSourceId === src.id;
            return (
              <button
                key={src.id}
                type="button"
                disabled={isLive || connecting}
                onClick={() => setSelectedSourceId(src.id)}
                className={`rounded-lg border px-3 py-2.5 text-left transition-colors disabled:opacity-60 ${
                  active
                    ? 'border-[#C8102E] bg-[#C8102E]/20 text-white'
                    : 'border-zinc-800 bg-zinc-950 text-zinc-300 hover:border-zinc-600'
                }`}
              >
                <div className="flex items-center justify-between gap-2">
                  <span className="text-xs font-bold">{src.label}</span>
                  {active && <CheckCircle2 className="h-3.5 w-3.5 text-[#C8102E] shrink-0" />}
                </div>
                <p className="mt-0.5 text-[11px] text-zinc-400">{src.category}</p>
              </button>
            );
          })}
        </div>

        {selectedSourceId === 'custom' && (
          <div className="grid grid-cols-1 gap-2 sm:grid-cols-2">
            <input
              type="text"
              disabled={isLive || connecting}
              value={customSourceName}
              onChange={(e) => setCustomSourceName(e.target.value)}
              placeholder="Nome do App nativo (ex: Star+, BandPlay, Player Local...)"
              className="w-full rounded-lg border border-zinc-700 bg-zinc-950 px-3 py-2 text-xs text-white focus:border-[#C8102E] focus:outline-none"
            />
            <input
              type="text"
              disabled={isLive || connecting}
              value={customPackageName}
              onChange={(e) => setCustomPackageName(e.target.value)}
              placeholder="Pacote Android opcional (ex: com.amazon.avod.thirdpartyclient)"
              className="w-full rounded-lg border border-zinc-700 bg-zinc-950 px-3 py-2 font-mono text-xs text-white focus:border-[#C8102E] focus:outline-none"
            />
          </div>
        )}
      </div>

      {/* Abertura no Celular + Permissão Android Nativa */}
      <div className="rounded-xl border border-zinc-800 bg-zinc-950 p-4 flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between">
        <div className="space-y-1">
          <div className="flex items-center gap-2 text-xs font-bold text-white">
            <Smartphone className="h-4 w-4 text-[#C8102E]" />
            <span>Abrir transmissão nos apps nativos do seu celular</span>
          </div>
          <p className="text-[11px] leading-relaxed text-zinc-400">
            {supportsWebDisplayCapture
              ? 'Toque para abrir o app selecionado no aparelho ou use o APK Android (com permissão QUERY_ALL_PACKAGES + MediaProjection) para abrir o app nativo direto durante o espelhamento.'
              : 'Seu navegador móvel bloqueia captura de outros apps pela aba. Abra o app desejado ou baixe o APK Transmissor Jogos do Mengão, que possui permissão nativa Android para abrir qualquer app instalado e transmitir ao vivo.'}
          </p>
        </div>
        <div className="flex flex-wrap items-center gap-2 shrink-0">
          <a
            href={mobileLaunchHref}
            target="_blank"
            rel="noopener noreferrer"
            className="inline-flex items-center gap-1.5 rounded-lg border border-zinc-700 bg-zinc-900 px-3.5 py-2 text-xs font-semibold text-white hover:bg-zinc-800 whitespace-nowrap"
          >
            <ExternalLink className="h-3.5 w-3.5 text-[#C8102E]" />
            Abrir {selectedSource} no Celular
          </a>
          <a
            href="/api/download-apk"
            download
            className="inline-flex items-center gap-1.5 rounded-lg border border-[#C8102E]/60 bg-[#C8102E]/15 px-3.5 py-2 text-xs font-semibold text-red-200 hover:bg-[#C8102E]/30 whitespace-nowrap"
          >
            <Download className="h-3.5 w-3.5" />
            APK Transmissor Android
          </a>
        </div>
      </div>

      {/* Passo 2: Título, Qualidade e Opções */}
      <div className="grid grid-cols-1 gap-4 md:grid-cols-12 md:items-end">
        <div className="md:col-span-5">
          <label className="block text-xs font-medium text-zinc-300">
            2. Título da transmissão no App
          </label>
          <input
            type="text"
            maxLength={120}
            disabled={isLive || connecting}
            value={broadcastTitle}
            onChange={(e) => setBroadcastTitle(e.target.value)}
            placeholder="Ex: Flamengo x Palmeiras · Ao Vivo"
            className="mt-1 w-full rounded-lg border border-zinc-700 bg-zinc-950 px-3 py-2 text-sm text-white focus:border-[#C8102E] focus:outline-none disabled:opacity-60"
          />
        </div>

        <div className="md:col-span-3">
          <label className="block text-xs font-medium text-zinc-300">Qualidade da imagem</label>
          <select
            disabled={isLive || connecting}
            value={quality}
            onChange={(e) => setQuality(e.target.value as QualityPreset)}
            className="mt-1 w-full rounded-lg border border-zinc-700 bg-zinc-950 px-3 py-2 text-sm text-white focus:border-[#C8102E] focus:outline-none disabled:opacity-60"
          >
            <option value="720p">{QUALITY_CONFIG['720p'].label}</option>
            <option value="1080p">{QUALITY_CONFIG['1080p'].label}</option>
            <option value="480p">{QUALITY_CONFIG['480p'].label}</option>
          </select>
        </div>

        <div className="md:col-span-4">
          {!isLive ? (
            <button
              type="button"
              disabled={connecting}
              onClick={startLiveBroadcast}
              className="inline-flex w-full items-center justify-center gap-2 rounded-lg bg-[#C8102E] px-4 py-2.5 text-sm font-semibold text-white hover:bg-red-700 disabled:opacity-50"
            >
              <Cast className="h-4 w-4" />
              {connecting ? 'Abrindo seletor de app...' : `Transmitir ${selectedSource} Ao Vivo`}
            </button>
          ) : (
            <button
              type="button"
              onClick={() => cleanupBroadcastResources(true)}
              className="inline-flex w-full items-center justify-center gap-2 rounded-lg border border-red-700 bg-red-950 px-4 py-2.5 text-sm font-semibold text-red-100 hover:bg-red-900"
            >
              <Square className="h-4 w-4" />
              Encerrar Transmissão
            </button>
          )}
        </div>
      </div>

      <div className="flex flex-wrap items-center gap-6 text-xs text-zinc-300">
        <label className="flex items-center gap-2 cursor-pointer">
          <input
            type="checkbox"
            disabled={isLive || connecting}
            checked={captureAudio}
            onChange={(e) => setCaptureAudio(e.target.checked)}
            className="h-4 w-4 accent-[#C8102E]"
          />
          <span>Capturar áudio do app escolhido</span>
        </label>
        <label className="flex items-center gap-2 cursor-pointer">
          <input
            type="checkbox"
            disabled={isLive || connecting}
            checked={autoPublishOnStart}
            onChange={(e) => setAutoPublishOnStart(e.target.checked)}
            className="h-4 w-4 accent-[#C8102E]"
          />
          <span>Auto-publicar Site e APK na área de transmissão ao entrar ao vivo</span>
        </label>
      </div>

      {errorMsg && (
        <p className="rounded-lg border border-red-900/60 bg-red-950/60 px-4 py-2.5 text-xs text-red-200">
          {errorMsg}
        </p>
      )}
      {statusMsg && !errorMsg && (
        <p className="rounded-lg border border-emerald-900/60 bg-emerald-950/50 px-4 py-2.5 text-xs text-emerald-200">
          {statusMsg}
        </p>
      )}

      {/* Prévia da transmissão ao vivo */}
      <div className="overflow-hidden rounded-xl border border-zinc-800 bg-black">
        <div className="flex items-center justify-between border-b border-zinc-800 bg-zinc-950 px-4 py-2 text-xs text-zinc-400">
          <span className="flex items-center gap-2">
            <MonitorPlay className="h-3.5 w-3.5 text-[#C8102E]" />
            {isLive ? `Fonte ativa: ${selectedSource}` : 'Prévia da captura do app selecionado'}
          </span>
          <span className="flex items-center gap-3 font-mono text-[11px]">
            {isLive && (
              <>
                <span className="inline-flex items-center gap-1">
                  {hasCapturedAudio ? (
                    <>
                      <Volume2 className="h-3.5 w-3.5 text-emerald-400" />
                      Áudio ON
                    </>
                  ) : (
                    <>
                      <VolumeX className="h-3.5 w-3.5 text-zinc-500" />
                      Sem áudio
                    </>
                  )}
                </span>
                <span>{framesSent} quadros</span>
              </>
            )}
          </span>
        </div>

        <div className="relative flex min-h-[240px] items-center justify-center">
          <video
            ref={previewVideoRef}
            muted
            playsInline
            className={`max-h-[360px] w-full object-contain ${isLive ? 'block' : 'hidden'}`}
          />
          {!isLive && (
            <div className="p-8 text-center space-y-2">
              <Tv className="mx-auto h-9 w-9 text-zinc-600" />
              <p className="text-xs font-semibold text-zinc-300">
                Escolha um App acima e clique em &ldquo;Transmitir {selectedSource} Ao Vivo&rdquo;
              </p>
              <p className="text-[11px] text-zinc-500">
                O seletor nativo do sistema abrirá para você clicar na janela/guia/tela do app.
              </p>
            </div>
          )}
        </div>
      </div>
    </section>
  );
}
