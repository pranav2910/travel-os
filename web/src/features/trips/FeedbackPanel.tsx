import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { learning, learningKeys } from '@/api/learning';
import { FEEDBACK_TAGS, type TripResponse } from '@/api/types';
import { finish, keyFor } from '@/lib/idempotency';
import { describe } from '@/lib/problem';
import { Alert, Button, Card, Field } from '@/ui';

/** Structured feedback only feeds learning; the comment is kept for a person and never used. */
export function FeedbackPanel({ trip, mine }: { trip: TripResponse; mine: boolean }) {
  const qc = useQueryClient();
  const existing = useQuery({
    queryKey: learningKeys.feedback(trip.tripId),
    queryFn: () => learning.feedback(trip.tripId),
  });
  const [component, setComponent] = useState('');
  const [rating, setRating] = useState(4);
  const [tags, setTags] = useState<string[]>([]);
  const [comment, setComment] = useState('');
  const send = useMutation({
    mutationFn: () => {
      const body = {
        tripId: trip.tripId,
        componentId: component || undefined,
        rating,
        tags,
        comment: comment || undefined,
      };
      return learning.recordFeedback(body, keyFor('feedback', body));
    },
    onSuccess: (_, __) => {
      finish('feedback', {
        tripId: trip.tripId,
        componentId: component || undefined,
        rating,
        tags,
        comment: comment || undefined,
      });
      void qc.invalidateQueries({ queryKey: learningKeys.feedback(trip.tripId) });
    },
  });
  const components = (trip.components ?? []).filter(
    (c) => c.status === 'CONFIRMED' || c.status === 'CHANGED',
  );
  return (
    <Card title="Your feedback">
      {existing.data && existing.data.length > 0 && (
        <ul>
          {existing.data.map((f) => (
            <li key={f.feedbackId}>
              Revision {f.revision}: {f.rating}/5 {f.tags.join(', ')}{' '}
              {f.supplierKey ? `(${f.supplierKey})` : ''}
            </li>
          ))}
        </ul>
      )}
      {mine ? (
        <form
          className="stack"
          onSubmit={(e) => {
            e.preventDefault();
            send.mutate();
          }}
        >
          <div className="form-grid">
            <Field label="Component" hint="Which part of the trip you are rating.">
              {(p) => (
                <select {...p} value={component} onChange={(e) => setComponent(e.target.value)}>
                  <option value="">The whole trip</option>
                  {components.map((c) => (
                    <option key={c.componentId} value={c.componentId}>
                      {c.type}: {c.summary ?? c.componentId}
                    </option>
                  ))}
                </select>
              )}
            </Field>
            <Field label="Rating (1 = poor, 5 = excellent)">
              {(p) => (
                <input
                  {...p}
                  type="number"
                  min={1}
                  max={5}
                  value={rating}
                  onChange={(e) => setRating(Number(e.target.value))}
                />
              )}
            </Field>
          </div>
          <fieldset className="fieldset">
            <legend>Tags</legend>
            <div className="checks">
              {FEEDBACK_TAGS.map((t) => (
                <label key={t}>
                  <input
                    type="checkbox"
                    checked={tags.includes(t)}
                    onChange={(e) =>
                      setTags(e.target.checked ? [...tags, t] : tags.filter((x) => x !== t))
                    }
                  />{' '}
                  {t.replaceAll('_', ' ').toLowerCase()}
                </label>
              ))}
            </div>
          </fieldset>
          <Field
            label="Comment (optional)"
            hint="Read by people. It is never parsed and never influences rankings."
          >
            {(p) => (
              <textarea
                {...p}
                rows={2}
                maxLength={2000}
                value={comment}
                onChange={(e) => setComment(e.target.value)}
              />
            )}
          </Field>
          {send.error && <Alert tone="danger">{describe(send.error)}</Alert>}
          {send.isSuccess && (
            <Alert tone="ok">
              Recorded as revision {send.data.revision}. Sending the same values again does not
              create another revision.
            </Alert>
          )}
          <div>
            <Button type="submit" variant="primary" busy={send.isPending}>
              Send feedback
            </Button>
          </div>
        </form>
      ) : (
        <p className="muted">Only the traveler gives feedback on a trip.</p>
      )}
    </Card>
  );
}
