import { Injectable, effect, inject, signal } from '@angular/core';
import { ApiService, errorMessage } from './api.service';
import { AuthService } from './auth.service';
import { ChatMessage, ValidationStatus } from './models';

const PREFIX = 'assistant.copilot.';
/** Nombre maximal de messages conservés (évite de saturer le stockage du navigateur). */
const MAX_STORED = 200;
/** Nombre de messages précédents envoyés au modèle comme contexte. */
const MAX_HISTORY_SENT = 20;

interface PersistedState {
  messages: ChatMessage[];
  tab: 'copilot' | 'recommendations';
  statusFilter: ValidationStatus | '';
}

/**
 * État du copilote IA, partagé à l'échelle de l'application (singleton).
 *
 * - Le composant « Assistant IA » est détruit à chaque changement d'écran : la
 *   conversation vit donc ici et non dans le composant.
 * - Elle est aussi sauvegardée dans le localStorage, avec une clé propre à
 *   chaque utilisateur : elle est retrouvée après une déconnexion / reconnexion
 *   ou un rechargement de la page, sans être visible par un autre compte.
 * - Une question envoyée continue d'être traitée même si l'utilisateur quitte
 *   l'écran : la réponse s'affiche à son retour.
 */
@Injectable({ providedIn: 'root' })
export class AiChatStore {
  private readonly api = inject(ApiService);
  private readonly auth = inject(AuthService);

  readonly messages = signal<ChatMessage[]>([]);
  readonly thinking = signal(false);
  readonly tab = signal<'copilot' | 'recommendations'>('copilot');
  readonly statusFilter = signal<ValidationStatus | ''>('');
  /** Brouillon de la question en cours de saisie. */
  draft = '';

  private loadedFor: string | null = null;

  constructor() {
    // Recharge la conversation quand l'utilisateur connecté change.
    effect(() => {
      const username = this.auth.user()?.username ?? null;
      if (username !== this.loadedFor) {
        this.loadedFor = username;
        this.restore(username);
      }
    });
    // Sauvegarde automatique à chaque modification.
    effect(() => {
      const state: PersistedState = {
        messages: this.messages().slice(-MAX_STORED),
        tab: this.tab(),
        statusFilter: this.statusFilter(),
      };
      const user = this.loadedFor;
      if (!user) return;
      try {
        localStorage.setItem(PREFIX + user, JSON.stringify(state));
      } catch {
        /* stockage indisponible (navigation privée…) : la conversation reste en mémoire */
      }
    });
  }

  ask(question: string): void {
    const q = question.trim();
    if (!q || this.thinking()) return;
    const history = this.messages().slice(-MAX_HISTORY_SENT);
    const owner = this.loadedFor;
    this.messages.update((m) => [...m, { role: 'user', content: q }]);
    this.thinking.set(true);
    this.api.chat(q, history).subscribe({
      next: (res) => this.append(owner, { role: 'assistant', content: res.answer, provider: `${res.provider} · ${res.model}` }),
      error: (e) => this.append(owner, { role: 'assistant', content: `⚠️ ${errorMessage(e)}`, provider: 'erreur' }),
    });
  }

  clear(): void {
    this.messages.set([]);
  }

  /** Ajoute la réponse uniquement si le même utilisateur est toujours connecté. */
  private append(owner: string | null, message: ChatMessage): void {
    this.thinking.set(false);
    if (owner === this.loadedFor) {
      this.messages.update((m) => [...m, message]);
      return;
    }
    // L'utilisateur s'est déconnecté pendant la réponse : on la range quand même dans sa conversation.
    if (!owner) return;
    try {
      const raw = localStorage.getItem(PREFIX + owner);
      const s = (raw ? JSON.parse(raw) : { messages: [], tab: 'copilot', statusFilter: '' }) as PersistedState;
      s.messages = [...(s.messages ?? []), message].slice(-MAX_STORED);
      localStorage.setItem(PREFIX + owner, JSON.stringify(s));
    } catch {
      /* ignoré */
    }
  }

  private restore(username: string | null): void {
    this.thinking.set(false);
    this.messages.set([]);
    this.tab.set('copilot');
    this.statusFilter.set('');
    if (!username) return;
    try {
      const raw = localStorage.getItem(PREFIX + username);
      if (!raw) return;
      const s = JSON.parse(raw) as Partial<PersistedState>;
      if (Array.isArray(s.messages)) this.messages.set(s.messages);
      if (s.tab === 'copilot' || s.tab === 'recommendations') this.tab.set(s.tab);
      if (s.statusFilter !== undefined) this.statusFilter.set(s.statusFilter);
    } catch {
      /* données corrompues : on repart d'une conversation vide */
    }
  }
}
