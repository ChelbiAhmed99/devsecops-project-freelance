import { Component, ElementRef, OnInit, computed, inject, signal, viewChild } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ApiService, errorMessage } from '../../core/api.service';
import { AiRecommendation, ChatMessage, ValidationStatus } from '../../core/models';
import { ToastService } from '../../core/toast.service';
import { SHARED_PIPES } from '../../shared/pipes';

@Component({
  selector: 'app-ai',
  standalone: true,
  imports: [FormsModule, ...SHARED_PIPES],
  templateUrl: './ai.component.html',
})
export class AiComponent implements OnInit {
  private readonly api = inject(ApiService);
  private readonly toasts = inject(ToastService);
  private readonly chatLog = viewChild<ElementRef<HTMLDivElement>>('chatLog');

  readonly tab = signal<'copilot' | 'recommendations'>('copilot');
  readonly recommendations = signal<AiRecommendation[]>([]);
  readonly statusFilter = signal<ValidationStatus | ''>('PENDING_REVIEW');
  readonly comments: Record<string, string> = {};
  readonly health = signal<{ status: string; provider?: string; model?: string } | null>(null);

  readonly messages = signal<ChatMessage[]>([]);
  readonly thinking = signal(false);
  question = '';

  readonly suggestions = [
    'Quelles sont les priorités à traiter aujourd\'hui ?',
    'Quelles vulnérabilités critiques dois-je corriger en premier ?',
    'Pourquoi des tests échouent-ils ?',
    'Quels services produisent le plus d\'anomalies ?',
  ];

  readonly filtered = computed(() => {
    const f = this.statusFilter();
    return f ? this.recommendations().filter((r) => r.status === f) : this.recommendations();
  });
  readonly counts = computed(() => {
    const list = this.recommendations();
    return {
      pending: list.filter((r) => r.status === 'PENDING_REVIEW').length,
      approved: list.filter((r) => r.status === 'APPROVED').length,
      rejected: list.filter((r) => r.status === 'REJECTED').length,
    };
  });

  ngOnInit(): void {
    this.loadRecommendations();
    this.api.aiHealth().subscribe({ next: (h) => this.health.set(h), error: () => this.health.set({ status: 'DOWN' }) });
  }

  loadRecommendations(): void {
    this.api.recommendations().subscribe({
      next: (r) => this.recommendations.set(r),
      error: (e) => this.toasts.error('Recommandations indisponibles', errorMessage(e)),
    });
  }

  review(r: AiRecommendation, status: 'APPROVED' | 'REJECTED'): void {
    this.api.reviewRecommendation(r.id, status, this.comments[r.id] || undefined).subscribe({
      next: () => {
        this.toasts.success(status === 'APPROVED' ? 'Recommandation validée' : 'Recommandation rejetée');
        this.loadRecommendations();
      },
      error: (e) => this.toasts.error('Échec', errorMessage(e)),
    });
  }

  ask(text?: string): void {
    const question = (text ?? this.question).trim();
    if (!question || this.thinking()) return;
    const history = this.messages();
    this.messages.update((m) => [...m, { role: 'user', content: question }]);
    this.question = '';
    this.thinking.set(true);
    this.scrollChat();
    this.api.chat(question, history).subscribe({
      next: (res) => {
        this.messages.update((m) => [...m, { role: 'assistant', content: res.answer, provider: `${res.provider} · ${res.model}` }]);
        this.thinking.set(false);
        this.scrollChat();
      },
      error: (e) => {
        this.messages.update((m) => [...m, { role: 'assistant', content: `⚠️ ${errorMessage(e)}`, provider: 'erreur' }]);
        this.thinking.set(false);
        this.scrollChat();
      },
    });
  }

  resetChat(): void {
    this.messages.set([]);
  }

  typeLabel(t: string): string {
    return ({ log_anomaly: 'Anomalie de log', test_failure: 'Échec de test', vulnerability: 'Vulnérabilité' } as Record<string, string>)[t] ?? 'Autre';
  }

  private scrollChat(): void {
    setTimeout(() => {
      const el = this.chatLog()?.nativeElement;
      if (el) el.scrollTop = el.scrollHeight;
    });
  }
}
