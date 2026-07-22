// ============================================================================
// VOICE ASSISTANT
// Voice commands | Voice search | Voice analysis | Read alerts aloud
// Built on the Web Speech API (SpeechRecognition + SpeechSynthesis).
// ============================================================================

import { TradingEventBus } from '../core/event-bus';

// Minimal type declarations for Web Speech API (not in standard TS lib DOM)
interface SpeechRecognitionResultLike { transcript: string; confidence: number; }
interface SpeechRecognitionEventLike { results: { [k: number]: { [k: number]: SpeechRecognitionResultLike; isFinal: boolean } }; resultIndex: number; }
interface SpeechRecognitionLike {
  lang: string; continuous: boolean; interimResults: boolean;
  start(): void; stop(): void; abort(): void;
  onresult: ((e: SpeechRecognitionEventLike) => void) | null;
  onerror: ((e: any) => void) | null;
  onend: (() => void) | null;
}

export type VoiceCommandType =
  | 'CHANGE_SYMBOL' | 'CHANGE_TIMEFRAME' | 'BUY' | 'SELL' | 'CLOSE_ALL'
  | 'CLOSE_POSITION' | 'SHOW_SCANNER' | 'RUN_ANALYSIS' | 'START_REPLAY'
  | 'STOP_REPLAY' | 'ASK_MENTOR' | 'SHOW_JOURNAL' | 'READ_ALERTS'
  | 'SET_ALERT' | 'UNKNOWN';

export interface ParsedCommand {
  type: VoiceCommandType;
  params: Record<string, string>;
  transcript: string;
  confidence: number;
}

export interface VoiceConfig {
  language: string;
  autoReadAlerts: boolean;
  voiceRate: number;   // 0.1 - 10
  voicePitch: number;  // 0 - 2
  voiceVolume: number; // 0 - 1
  wakeWord?: string;   // Optional wake word (e.g. "trader")
}

const DEFAULT_CONFIG: VoiceConfig = {
  language: 'en-US',
  autoReadAlerts: true,
  voiceRate: 1.0,
  voicePitch: 1.0,
  voiceVolume: 1.0,
};

export type CommandHandler = (command: ParsedCommand) => void;

export class VoiceAssistant {
  private config: VoiceConfig;
  private eventBus?: TradingEventBus;
  private recognition: SpeechRecognitionLike | null = null;
  private synth: SpeechSynthesis | null = null;
  private listening: boolean = false;
  private commandHandler?: CommandHandler;
  private speechQueue: string[] = [];
  private speaking: boolean = false;

  // Symbol name normalization for voice ("euro dollar" -> "EURUSD")
  private symbolAliases: Record<string, string> = {
    'euro dollar': 'EURUSD', 'eurusd': 'EURUSD', 'euro': 'EURUSD',
    'cable': 'GBPUSD', 'pound dollar': 'GBPUSD', 'gbpusd': 'GBPUSD',
    'dollar yen': 'USDJPY', 'usdjpy': 'USDJPY',
    'gold': 'XAUUSD', 'xauusd': 'XAUUSD',
    'bitcoin': 'BTCUSD', 'btc': 'BTCUSD',
    'ethereum': 'ETHUSD', 'eth': 'ETHUSD',
    'nasdaq': 'NAS100', 'nas': 'NAS100',
    'dow': 'US30', 'dow jones': 'US30',
    'sp 500': 'US500', 's and p': 'US500',
  };

  private timeframeAliases: Record<string, string> = {
    'one minute': 'M1', 'M1': 'M1', 'one min': 'M1',
    'five minute': 'M5', 'five min': 'M5', 'M5': 'M5',
    'fifteen minute': 'M15', 'fifteen min': 'M15',
    'thirty minute': 'M30', 'half hour': 'M30',
    'one hour': 'H1', 'hourly': 'H1',
    'four hour': 'H4', 'four hours': 'H4',
    'daily': 'D1', 'day': 'D1',
    'weekly': 'W1', 'week': 'W1',
  };

  constructor(config: Partial<VoiceConfig> = {}, eventBus?: TradingEventBus) {
    this.config = { ...DEFAULT_CONFIG, ...config };
    this.eventBus = eventBus;
    this.initialize();
  }

  // =========================================================================
  // INITIALIZATION
  // =========================================================================

  private initialize(): void {
    if (typeof window === 'undefined') return;

    // Speech Recognition
    const SR = (window as any).SpeechRecognition || (window as any).webkitSpeechRecognition;
    if (SR) {
      this.recognition = new SR();
      this.recognition!.lang = this.config.language;
      this.recognition!.continuous = true;
      this.recognition!.interimResults = false;

      this.recognition!.onresult = (event: SpeechRecognitionEventLike) => {
        const result = event.results[event.resultIndex];
        if (result && result.isFinal) {
          const transcript = result[0].transcript.trim();
          const confidence = result[0].confidence;
          this.handleTranscript(transcript, confidence);
        }
      };

      this.recognition!.onerror = (e: any) => {
        console.warn('[Voice] Recognition error:', e.error);
      };

      this.recognition!.onend = () => {
        if (this.listening) {
          // Auto-restart for continuous listening
          try { this.recognition!.start(); } catch { /* already started */ }
        }
      };
    } else {
      console.warn('[Voice] SpeechRecognition not supported in this browser');
    }

    // Speech Synthesis
    this.synth = (window as any).speechSynthesis || null;
    if (!this.synth) console.warn('[Voice] SpeechSynthesis not supported');
  }

  // =========================================================================
  // LISTENING CONTROL
  // =========================================================================

  startListening(): boolean {
    if (!this.recognition) return false;
    try {
      this.recognition.start();
      this.listening = true;
      this.speak('Voice assistant activated. How can I help you trade?');
      return true;
    } catch (err) {
      console.warn('[Voice] Failed to start:', err);
      return false;
    }
  }

  stopListening(): void {
    if (this.recognition) {
      this.listening = false;
      this.recognition.stop();
    }
  }

  isListening(): boolean { return this.listening; }

  // =========================================================================
  // COMMAND PARSING
  // =========================================================================

  private handleTranscript(transcript: string, confidence: number): void {
    // Wake word check
    if (this.config.wakeWord && !transcript.toLowerCase().includes(this.config.wakeWord.toLowerCase())) {
      return;
    }

    const command = this.parseCommand(transcript, confidence);
    this.eventBus?.emit({ type: 'VOICE_COMMAND' as any, data: command });
    this.commandHandler?.(command);
  }

  /**
   * Parse a spoken transcript into a structured command
   */
  parseCommand(transcript: string, confidence: number = 1): ParsedCommand {
    const lower = transcript.toLowerCase();
    const params: Record<string, string> = {};

    // Trading commands
    if (/\b(buy|go long|long)\b/.test(lower)) {
      const symbol = this.extractSymbol(lower);
      if (symbol) params.symbol = symbol;
      return { type: 'BUY', params, transcript, confidence };
    }
    if (/\b(sell|go short|short)\b/.test(lower)) {
      const symbol = this.extractSymbol(lower);
      if (symbol) params.symbol = symbol;
      return { type: 'SELL', params, transcript, confidence };
    }
    if (/close all|flatten|exit all/.test(lower)) {
      return { type: 'CLOSE_ALL', params, transcript, confidence };
    }
    if (/close position|close trade|exit position/.test(lower)) {
      return { type: 'CLOSE_POSITION', params, transcript, confidence };
    }

    // Navigation / symbol / timeframe
    if (/switch to|change to|show|open|load/.test(lower)) {
      const symbol = this.extractSymbol(lower);
      const timeframe = this.extractTimeframe(lower);
      if (symbol) { params.symbol = symbol; return { type: 'CHANGE_SYMBOL', params, transcript, confidence }; }
      if (timeframe) { params.timeframe = timeframe; return { type: 'CHANGE_TIMEFRAME', params, transcript, confidence }; }
      if (/scanner/.test(lower)) return { type: 'SHOW_SCANNER', params, transcript, confidence };
      if (/journal/.test(lower)) return { type: 'SHOW_JOURNAL', params, transcript, confidence };
    }

    // Timeframe standalone
    const tf = this.extractTimeframe(lower);
    if (tf && /timeframe|chart|switch/.test(lower)) {
      params.timeframe = tf;
      return { type: 'CHANGE_TIMEFRAME', params, transcript, confidence };
    }

    // Analysis / mentor
    if (/analyz|analys|scan (this|the)|what.*setup/.test(lower)) {
      return { type: 'RUN_ANALYSIS', params, transcript, confidence };
    }
    if (/why|should i|where.*(stop|target)|what.*bias|explain/.test(lower)) {
      params.question = transcript;
      return { type: 'ASK_MENTOR', params, transcript, confidence };
    }

    // Replay
    if (/start replay|begin replay|replay/.test(lower)) return { type: 'START_REPLAY', params, transcript, confidence };
    if (/stop replay|end replay/.test(lower)) return { type: 'STOP_REPLAY', params, transcript, confidence };

    // Alerts
    if (/read alert|what.*alert|any alert/.test(lower)) return { type: 'READ_ALERTS', params, transcript, confidence };
    if (/set.*alert|alert me|notify/.test(lower)) {
      const symbol = this.extractSymbol(lower);
      if (symbol) params.symbol = symbol;
      const priceMatch = lower.match(/at ([\d.]+)/);
      if (priceMatch) params.price = priceMatch[1];
      return { type: 'SET_ALERT', params, transcript, confidence };
    }

    return { type: 'UNKNOWN', params, transcript, confidence };
  }

  private extractSymbol(text: string): string | null {
    for (const [alias, symbol] of Object.entries(this.symbolAliases)) {
      if (text.includes(alias)) return symbol;
    }
    // Direct symbol match (e.g. "EURUSD")
    const match = text.match(/\b([a-z]{6}|[a-z]{3}\d{2,3})\b/);
    if (match) return match[1].toUpperCase();
    return null;
  }

  private extractTimeframe(text: string): string | null {
    for (const [alias, tf] of Object.entries(this.timeframeAliases)) {
      if (text.includes(alias)) return tf;
    }
    return null;
  }

  // =========================================================================
  // TEXT-TO-SPEECH
  // =========================================================================

  /**
   * Speak text aloud (queued to avoid overlapping)
   */
  speak(text: string): void {
    if (!this.synth) return;
    this.speechQueue.push(text);
    if (!this.speaking) this.processSpeechQueue();
  }

  private processSpeechQueue(): void {
    if (!this.synth || this.speechQueue.length === 0) {
      this.speaking = false;
      return;
    }
    this.speaking = true;
    const text = this.speechQueue.shift()!;
    const utterance = new SpeechSynthesisUtterance(text);
    utterance.lang = this.config.language;
    utterance.rate = this.config.voiceRate;
    utterance.pitch = this.config.voicePitch;
    utterance.volume = this.config.voiceVolume;
    utterance.onend = () => this.processSpeechQueue();
    utterance.onerror = () => this.processSpeechQueue();
    this.synth.speak(utterance);
  }

  /**
   * Read a scanner alert aloud
   */
  readAlert(alert: { type: string; symbol: string; direction: string; confidence: number; message: string }): void {
    if (!this.config.autoReadAlerts) return;
    const text = `${alert.direction} signal on ${this.spellSymbol(alert.symbol)}. ${alert.type.replace(/_/g, ' ')}. Confidence ${alert.confidence} percent.`;
    this.speak(text);
  }

  /**
   * Read a full AI analysis / mentor response aloud
   */
  readAnalysis(text: string): void {
    // Strip symbols/emojis for cleaner speech
    const clean = text.replace(/[⚠️✓💧▶]/g, '').replace(/\s+/g, ' ').trim();
    this.speak(clean);
  }

  /**
   * Spell out a symbol for clearer speech (EURUSD -> "E U R U S D" is too much;
   * instead use friendly names)
   */
  private spellSymbol(symbol: string): string {
    const friendly: Record<string, string> = {
      'EURUSD': 'Euro Dollar', 'GBPUSD': 'Pound Dollar', 'USDJPY': 'Dollar Yen',
      'XAUUSD': 'Gold', 'BTCUSD': 'Bitcoin', 'ETHUSD': 'Ethereum',
      'NAS100': 'Nasdaq', 'US30': 'Dow', 'US500': 'S and P 500',
    };
    return friendly[symbol] || symbol;
  }

  stopSpeaking(): void {
    if (this.synth) { this.synth.cancel(); this.speechQueue = []; this.speaking = false; }
  }

  // =========================================================================
  // CONFIG & HANDLERS
  // =========================================================================

  onCommand(handler: CommandHandler): void { this.commandHandler = handler; }

  updateConfig(config: Partial<VoiceConfig>): void {
    this.config = { ...this.config, ...config };
    if (this.recognition) this.recognition.lang = this.config.language;
  }

  isSupported(): { recognition: boolean; synthesis: boolean } {
    return { recognition: this.recognition !== null, synthesis: this.synth !== null };
  }

  addSymbolAlias(alias: string, symbol: string): void {
    this.symbolAliases[alias.toLowerCase()] = symbol.toUpperCase();
  }

  destroy(): void {
    this.stopListening();
    this.stopSpeaking();
  }
}
