import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api, ApiError } from '../api/client';
import { useApp } from '../store/app';
import { C, rule1, rule2 } from '../theme/colors';
import { StatusTag } from './Promote';
import type { PromotionView } from '../api/types';

const mono = 'var(--mono)';

export function Approvals() {
  const { flash } = useApp();
  const qc = useQueryClient();
  const [denyId, setDenyId] = useState<string | null>(null);
  const [reason, setReason] = useState('');

  const { data: pending } = useQuery({ queryKey: ['approvals', 'pending'], queryFn: () => api.get<PromotionView[]>('/approvals/pending') });
  const { data: decided } = useQuery({ queryKey: ['approvals', 'decided'], queryFn: () => api.get<PromotionView[]>('/approvals/decided') });

  const invalidate = () => { qc.invalidateQueries({ queryKey: ['approvals'] }); qc.invalidateQueries({ queryKey: ['deploy'] }); qc.invalidateQueries({ queryKey: ['registry'] }); };

  const approve = useMutation({
    mutationFn: (id: string) => api.post<PromotionView>(`/approvals/${id}/approve`),
    onSuccess: (p) => { invalidate(); flash(`Approved ${p.id} (${p.app}) — now deployable`); },
    onError: (e) => flash(e instanceof ApiError ? e.message : 'approve failed', C.stop),
  });
  const deny = useMutation({
    mutationFn: (v: { id: string; reason: string }) => api.post<PromotionView>(`/approvals/${v.id}/deny`, { reason: v.reason }),
    onSuccess: (p) => { invalidate(); setDenyId(null); setReason(''); flash(`Denied ${p.id} (${p.app}) — requester emailed`, C.warn); },
    onError: (e) => flash(e instanceof ApiError ? e.message : 'deny failed', C.stop),
  });

  return (
    <main style={{ padding: '0 24px 56px', maxWidth: 1400 }}>
      <div style={{ display: 'flex', alignItems: 'baseline', gap: 14, padding: '20px 0 12px' }}>
        <h3 style={{ margin: 0, color: 'var(--color-neutral-100)' }}>Approvals</h3>
        <div style={{ fontSize: 12.5, color: 'var(--color-neutral-500)' }}>Super-admin gate — nothing reaches production without a decision here.</div>
      </div>

      <h6 style={{ color: 'var(--color-neutral-400)', margin: '18px 0 6px' }}>PENDING</h6>
      <div style={{ borderTop: rule2 }}>
        {pending?.length === 0 && <div style={{ padding: '14px 6px', fontSize: 12.5, color: 'var(--color-neutral-500)' }}>Nothing pending. The queue is clear.</div>}
        {pending?.map((pn) => (
          <div key={pn.id} style={{ borderBottom: rule1, padding: '16px 6px' }}>
            <div style={{ display: 'grid', gridTemplateColumns: '1fr 220px auto', gap: 24, alignItems: 'center' }}>
              <div>
                <div style={{ display: 'flex', alignItems: 'center', gap: 10, marginBottom: 6 }}>
                  <span style={{ color: 'var(--color-accent-400)', fontFamily: mono, fontSize: 13, fontWeight: 700 }}>{pn.id}</span>
                  <span style={{ fontFamily: mono, fontSize: 15, color: 'var(--color-neutral-100)' }}>{pn.app}</span>
                  <StatusTag status={pn.status} />
                </div>
                <div style={{ fontFamily: mono, fontSize: 11.5, color: 'var(--color-neutral-400)' }}>{pn.jar} · {pn.size} · {pn.branch}</div>
                <div style={{ fontFamily: mono, fontSize: 11.5, color: 'var(--color-neutral-500)', marginTop: 3 }}>from {pn.srcHost} · dest group {pn.group} · fetched by {pn.requestedBy} · {pn.requestedAt}</div>
              </div>
              <div style={{ fontFamily: mono, fontSize: 13 }}>
                <div style={{ fontSize: 9, color: 'var(--color-neutral-500)', letterSpacing: '.1em', marginBottom: 2 }}>PROD → STAGED</div>
                <span style={{ color: 'var(--color-neutral-500)' }}>{pn.prevHash}</span> → <span style={{ color: '#22c55e', fontWeight: 700 }}>{pn.hash}</span>
              </div>
              <div style={{ display: 'flex', gap: 8 }}>
                <button onClick={() => { setDenyId(pn.id); setReason(''); }} style={{ border: '1px solid color-mix(in srgb, var(--color-neutral-100) 30%, transparent)', background: 'transparent', color: 'var(--color-neutral-200)', cursor: 'pointer', padding: '10px 16px', fontFamily: 'var(--font-heading)', fontWeight: 600, fontSize: 11, letterSpacing: '.1em' }}>DENY</button>
                <button onClick={() => approve.mutate(pn.id)} disabled={approve.isPending} style={{ border: 0, background: C.approve, color: '#0d1f14', cursor: 'pointer', padding: '10px 18px', fontFamily: 'var(--font-heading)', fontWeight: 800, fontSize: 11, letterSpacing: '.1em' }}>APPROVE ✓</button>
              </div>
            </div>
            {denyId === pn.id && (
              <div style={{ display: 'flex', gap: 10, alignItems: 'center', marginTop: 12, paddingTop: 12, borderTop: '1px dashed color-mix(in srgb, var(--color-neutral-100) 20%, transparent)' }}>
                <input value={reason} onChange={(e) => setReason(e.target.value)} placeholder="reason (emailed to requester)…"
                  style={{ flex: 1, background: 'var(--color-neutral-900)', border: '1px solid color-mix(in srgb, var(--color-neutral-100) 25%, transparent)', color: 'var(--color-neutral-100)', padding: '9px 10px', fontFamily: mono, fontSize: 12 }} />
                <button onClick={() => { setDenyId(null); setReason(''); }} style={{ border: '1px solid color-mix(in srgb, var(--color-neutral-100) 30%, transparent)', background: 'transparent', color: 'var(--color-neutral-300)', cursor: 'pointer', padding: '9px 14px', fontFamily: 'var(--font-heading)', fontWeight: 600, fontSize: 10, letterSpacing: '.1em' }}>CANCEL</button>
                <button onClick={() => deny.mutate({ id: pn.id, reason })} style={{ border: 0, background: 'var(--color-accent)', color: 'var(--color-neutral-100)', cursor: 'pointer', padding: '9px 16px', fontFamily: 'var(--font-heading)', fontWeight: 800, fontSize: 10, letterSpacing: '.1em' }}>CONFIRM DENY</button>
              </div>
            )}
          </div>
        ))}
      </div>

      <h6 style={{ color: 'var(--color-neutral-400)', margin: '26px 0 6px' }}>RECENTLY DECIDED</h6>
      <div style={{ display: 'grid', gridTemplateColumns: '90px 140px 1fr 130px 150px 110px', gap: 12, padding: '6px 8px', borderTop: rule2, borderBottom: rule1, fontFamily: 'var(--font-heading)', fontWeight: 600, fontSize: 9.5, letterSpacing: '.1em', color: 'var(--color-neutral-500)' }}>
        <div>REQUEST</div><div>APP</div><div>JAR · HASH</div><div>DECIDED BY</div><div>WHEN</div><div>OUTCOME</div>
      </div>
      {decided?.map((dc) => (
        <div key={dc.id} style={{ display: 'grid', gridTemplateColumns: '90px 140px 1fr 130px 150px 110px', gap: 12, padding: '9px 8px', borderBottom: rule1, fontFamily: mono, fontSize: 11.5, alignItems: 'center' }}>
          <div style={{ color: 'var(--color-accent-400)' }}>{dc.id}</div>
          <div style={{ color: 'var(--color-neutral-100)' }}>{dc.app}</div>
          <div style={{ color: 'var(--color-neutral-400)', whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis' }}>{dc.jar} · {dc.hash}</div>
          <div style={{ color: 'var(--color-neutral-300)' }}>{dc.decidedBy}</div>
          <div style={{ color: 'var(--color-neutral-500)' }}>{dc.decidedAt}</div>
          <StatusTag status={dc.status} />
        </div>
      ))}
    </main>
  );
}
