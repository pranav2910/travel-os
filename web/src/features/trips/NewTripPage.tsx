import { useEffect, useMemo, useState } from 'react';
import { useNavigate } from 'react-router';
import { useAuth } from '@/auth/AuthContext';
import { capabilities } from '@/auth/session';
import type { CreateTripRequest, LegRequest, StayRequest, TransferRequest } from '@/api/types';
import { pending } from '@/lib/idempotency';
import { utcInstant } from '@/lib/dates';
import { ApiError, UncertainError, describe } from '@/lib/problem';
import { Alert, Button, Card, Field, PageHeader } from '@/ui';
import { useCreateTrip } from './hooks';

type Mode = 'round' | 'oneway' | 'multi' | 'text';
interface LegForm {
  origin: string;
  destination: string;
  date: string;
  earliest: string;
  deadline: string;
}
interface StayForm {
  city: string;
  checkIn: string;
  checkOut: string;
}
interface TransferForm {
  kind: 'AIRPORT_TO_HOTEL' | 'HOTEL_TO_AIRPORT';
  city: string;
}

const IATA = /^[A-Z]{3}$/;
const emptyLeg = (): LegForm => ({
  origin: '',
  destination: '',
  date: '',
  earliest: '06:00',
  deadline: '23:00',
});

export function NewTripPage() {
  const { session } = useAuth();
  const navigate = useNavigate();
  const create = useCreateTrip();
  const [mode, setMode] = useState<Mode>('round');
  const [purpose, setPurpose] = useState('');
  const [text, setText] = useState('');
  const [travelerId, setTravelerId] = useState('');
  const [origin, setOrigin] = useState('BOS');
  const [destination, setDestination] = useState('SEA');
  const [outDate, setOutDate] = useState('');
  const [outEarliest, setOutEarliest] = useState('06:00');
  const [outDeadline, setOutDeadline] = useState('23:00');
  const [retDate, setRetDate] = useState('');
  const [retAfter, setRetAfter] = useState('10:00');
  const [retLatest, setRetLatest] = useState('23:00');
  const [hotelRequired, setHotelRequired] = useState(false);
  const [legs, setLegs] = useState<LegForm[]>([emptyLeg(), emptyLeg()]);
  const [stays, setStays] = useState<StayForm[]>([]);
  const [transfers, setTransfers] = useState<TransferForm[]>([]);
  const [currency] = useState('USD');
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [confirmed, setConfirmed] = useState(false);
  const unfinished = useMemo(() => pending('create-trip').length, []);

  useEffect(() => {
    if (create.data) navigate(`/trips/${create.data.tripId}`, { replace: true });
  }, [create.data, navigate]);

  function validate(): CreateTripRequest | null {
    const e: Record<string, string> = {};
    const body: CreateTripRequest = { source: 'WEB' };
    if (travelerId.trim()) body.travelerId = travelerId.trim();
    if (mode === 'text') {
      if (text.trim().length < 10) e['text'] = 'Describe the trip in a sentence or two.';
      body.request = text.trim();
    } else if (mode === 'multi') {
      const legOut: LegRequest[] = [];
      legs.forEach((l, i) => {
        if (!IATA.test(l.origin) || !IATA.test(l.destination))
          e[`leg${i}`] = 'Use three-letter airport codes (BOS, SEA).';
        else if (!l.date) e[`leg${i}`] = 'Choose the travel date.';
        else if (l.earliest >= l.deadline)
          e[`leg${i}`] = 'The arrival deadline must be after the earliest departure.';
        else
          legOut.push({
            origin: l.origin,
            destination: l.destination,
            earliestDeparture: utcInstant(l.date, l.earliest),
            arrivalDeadline: utcInstant(l.date, l.deadline),
          });
      });
      const stayOut: StayRequest[] = [];
      stays.forEach((s, i) => {
        if (!IATA.test(s.city) || !s.checkIn || !s.checkOut)
          e[`stay${i}`] = 'City code and both dates are required.';
        else if (s.checkOut <= s.checkIn) e[`stay${i}`] = 'Check-out must be after check-in.';
        else
          stayOut.push({
            city: s.city,
            checkInDate: s.checkIn,
            checkOutDate: s.checkOut,
            required: true,
          });
      });
      const xferOut: TransferRequest[] = transfers
        .filter((t) => IATA.test(t.city))
        .map((t) => ({ kind: t.kind, city: t.city, required: true }));
      transfers.forEach((t, i) => {
        if (!IATA.test(t.city)) e[`xfer${i}`] = 'Use the airport code of the city.';
      });
      body.intent = {
        purpose: purpose.trim() || undefined,
        itinerary: { legs: legOut, stays: stayOut, transfers: xferOut, currency },
      };
    } else {
      if (!IATA.test(origin) || !IATA.test(destination))
        e['route'] = 'Use three-letter airport codes (BOS, SEA).';
      if (!outDate) e['out'] = 'Choose the outbound date.';
      else if (outEarliest >= outDeadline)
        e['out'] = 'The arrival deadline must be after the earliest departure.';
      if (mode === 'round') {
        if (!retDate) e['ret'] = 'Choose the return date.';
        else if (outDate && retDate < outDate)
          e['ret'] = 'The return cannot be before the outbound.';
        else if (retAfter >= retLatest) e['ret'] = 'The latest return must be after the earliest.';
      }
      if (hotelRequired && mode !== 'round')
        e['hotel'] =
          'A hotel needs a return window: choose a round trip or describe the stay as an itinerary.';
      body.intent = {
        origin,
        destination,
        earliestDeparture: outDate ? utcInstant(outDate, outEarliest) : undefined,
        arrivalDeadline: outDate ? utcInstant(outDate, outDeadline) : undefined,
        returnAfter: mode === 'round' && retDate ? utcInstant(retDate, retAfter) : undefined,
        latestReturn: mode === 'round' && retDate ? utcInstant(retDate, retLatest) : undefined,
        purpose: purpose.trim() || undefined,
        hotelRequired: hotelRequired || undefined,
        travelers: 1,
      };
    }
    if (!purpose.trim() && mode !== 'text')
      e['purpose'] = 'Say what the trip is for; approvers read it.';
    setErrors(e);
    return Object.keys(e).length === 0 ? body : null;
  }

  function submit(ev: React.FormEvent) {
    ev.preventDefault();
    if (create.isPending) return;
    const body = validate();
    if (!body) return;
    if (!confirmed) {
      setConfirmed(true);
      return;
    }
    create.mutate(body);
  }

  const err = create.error;
  return (
    <>
      <PageHeader
        title="New trip"
        lead="Tell the platform what you need. It searches the simulated suppliers, applies your company's policy, optimizes, asks for approval when policy says so, and books."
      />
      {unfinished > 0 && !create.isPending && (
        <Alert tone="warn" title="A previous request was interrupted.">
          If it was submitted, it is in your trips list; submitting the same details again returns
          that same trip instead of a second one.
        </Alert>
      )}
      <form onSubmit={submit} noValidate className="stack" aria-describedby="submit-semantics">
        <Card title="What kind of trip">
          <div className="checks" role="radiogroup" aria-label="Trip kind">
            {(
              [
                ['round', 'Round trip'],
                ['oneway', 'One way'],
                ['multi', 'Multi-city itinerary (hotels, transfers)'],
                ['text', 'Describe it in words'],
              ] as [Mode, string][]
            ).map(([m, label]) => (
              <label key={m}>
                <input
                  type="radio"
                  name="mode"
                  value={m}
                  checked={mode === m}
                  onChange={() => {
                    setMode(m);
                    setConfirmed(false);
                  }}
                />{' '}
                {label}
              </label>
            ))}
          </div>
        </Card>
        {mode !== 'text' && (
          <Card title="Purpose">
            <Field
              label="Purpose of travel"
              error={errors['purpose']}
              hint="Shown to approvers and kept with the trip."
            >
              {(p) => (
                <input
                  {...p}
                  value={purpose}
                  onChange={(e) => setPurpose(e.target.value)}
                  maxLength={500}
                />
              )}
            </Field>
          </Card>
        )}
        {mode === 'text' && (
          <Card title="Your request">
            <Field
              label="Describe the trip"
              error={errors['text']}
              hint="For example: “Seattle before 5pm Tuesday Oct 6, back Wednesday evening, customer meeting.” A model extracts the need; you can see what it assumed on the trip page."
            >
              {(p) => (
                <textarea
                  {...p}
                  rows={4}
                  value={text}
                  onChange={(e) => setText(e.target.value)}
                  maxLength={4000}
                />
              )}
            </Field>
          </Card>
        )}
        {(mode === 'round' || mode === 'oneway') && (
          <Card title="Flights">
            <div className="form-grid">
              <Field label="From (airport code)" error={errors['route']}>
                {(p) => (
                  <input
                    {...p}
                    value={origin}
                    onChange={(e) => setOrigin(e.target.value.toUpperCase())}
                    maxLength={3}
                    autoCapitalize="characters"
                  />
                )}
              </Field>
              <Field label="To (airport code)">
                {(p) => (
                  <input
                    {...p}
                    value={destination}
                    onChange={(e) => setDestination(e.target.value.toUpperCase())}
                    maxLength={3}
                    autoCapitalize="characters"
                  />
                )}
              </Field>
            </div>
            <fieldset className="fieldset">
              <legend>Outbound</legend>
              <div className="form-grid">
                <Field label="Date" error={errors['out']}>
                  {(p) => (
                    <input
                      {...p}
                      type="date"
                      value={outDate}
                      onChange={(e) => setOutDate(e.target.value)}
                    />
                  )}
                </Field>
                <Field
                  label="Earliest departure (UTC)"
                  hint="The sandbox airline schedules on the UTC clock."
                >
                  {(p) => (
                    <input
                      {...p}
                      type="time"
                      value={outEarliest}
                      onChange={(e) => setOutEarliest(e.target.value)}
                    />
                  )}
                </Field>
                <Field label="Arrive by (UTC)">
                  {(p) => (
                    <input
                      {...p}
                      type="time"
                      value={outDeadline}
                      onChange={(e) => setOutDeadline(e.target.value)}
                    />
                  )}
                </Field>
              </div>
            </fieldset>
            {mode === 'round' && (
              <fieldset className="fieldset">
                <legend>Return</legend>
                <div className="form-grid">
                  <Field label="Date" error={errors['ret']}>
                    {(p) => (
                      <input
                        {...p}
                        type="date"
                        value={retDate}
                        onChange={(e) => setRetDate(e.target.value)}
                      />
                    )}
                  </Field>
                  <Field label="Earliest return (UTC)">
                    {(p) => (
                      <input
                        {...p}
                        type="time"
                        value={retAfter}
                        onChange={(e) => setRetAfter(e.target.value)}
                      />
                    )}
                  </Field>
                  <Field label="Latest return (UTC)">
                    {(p) => (
                      <input
                        {...p}
                        type="time"
                        value={retLatest}
                        onChange={(e) => setRetLatest(e.target.value)}
                      />
                    )}
                  </Field>
                </div>
              </fieldset>
            )}
            <div className="checks">
              <label>
                <input
                  type="checkbox"
                  checked={hotelRequired}
                  onChange={(e) => setHotelRequired(e.target.checked)}
                />{' '}
                A hotel is required for the nights between the flights
              </label>
            </div>
            {errors['hotel'] && (
              <span className="field__error" role="alert">
                {errors['hotel']}
              </span>
            )}
          </Card>
        )}
        {mode === 'multi' && (
          <>
            <Card
              title="Legs (in order)"
              actions={
                <Button
                  size="sm"
                  onClick={() => setLegs((l) => [...l, emptyLeg()])}
                  disabled={legs.length >= 8}
                >
                  Add leg
                </Button>
              }
            >
              {legs.map((l, i) => (
                <fieldset className="fieldset" key={i}>
                  <legend>Leg {i + 1}</legend>
                  <div className="form-grid">
                    <Field label="From" error={errors[`leg${i}`]}>
                      {(p) => (
                        <input
                          {...p}
                          value={l.origin}
                          maxLength={3}
                          onChange={(e) =>
                            setLegs(
                              legs.map((x, j) =>
                                j === i ? { ...x, origin: e.target.value.toUpperCase() } : x,
                              ),
                            )
                          }
                        />
                      )}
                    </Field>
                    <Field label="To">
                      {(p) => (
                        <input
                          {...p}
                          value={l.destination}
                          maxLength={3}
                          onChange={(e) =>
                            setLegs(
                              legs.map((x, j) =>
                                j === i ? { ...x, destination: e.target.value.toUpperCase() } : x,
                              ),
                            )
                          }
                        />
                      )}
                    </Field>
                    <Field label="Date">
                      {(p) => (
                        <input
                          {...p}
                          type="date"
                          value={l.date}
                          onChange={(e) =>
                            setLegs(
                              legs.map((x, j) => (j === i ? { ...x, date: e.target.value } : x)),
                            )
                          }
                        />
                      )}
                    </Field>
                    <Field label="Earliest departure (UTC)">
                      {(p) => (
                        <input
                          {...p}
                          type="time"
                          value={l.earliest}
                          onChange={(e) =>
                            setLegs(
                              legs.map((x, j) =>
                                j === i ? { ...x, earliest: e.target.value } : x,
                              ),
                            )
                          }
                        />
                      )}
                    </Field>
                    <Field label="Arrive by (UTC)">
                      {(p) => (
                        <input
                          {...p}
                          type="time"
                          value={l.deadline}
                          onChange={(e) =>
                            setLegs(
                              legs.map((x, j) =>
                                j === i ? { ...x, deadline: e.target.value } : x,
                              ),
                            )
                          }
                        />
                      )}
                    </Field>
                  </div>
                  {legs.length > 1 && (
                    <Button
                      size="sm"
                      variant="ghost"
                      onClick={() => setLegs(legs.filter((_, j) => j !== i))}
                    >
                      Remove leg {i + 1}
                    </Button>
                  )}
                </fieldset>
              ))}
            </Card>
            <Card
              title="Hotel stays"
              actions={
                <Button
                  size="sm"
                  onClick={() => setStays((s) => [...s, { city: '', checkIn: '', checkOut: '' }])}
                >
                  Add stay
                </Button>
              }
            >
              {stays.length === 0 && (
                <p className="muted">
                  No stays. Add one per city; dates are the property's local dates.
                </p>
              )}
              {stays.map((s, i) => (
                <fieldset className="fieldset" key={i}>
                  <legend>Stay {i + 1}</legend>
                  <div className="form-grid">
                    <Field label="City (airport code)" error={errors[`stay${i}`]}>
                      {(p) => (
                        <input
                          {...p}
                          value={s.city}
                          maxLength={3}
                          onChange={(e) =>
                            setStays(
                              stays.map((x, j) =>
                                j === i ? { ...x, city: e.target.value.toUpperCase() } : x,
                              ),
                            )
                          }
                        />
                      )}
                    </Field>
                    <Field label="Check-in (local date)">
                      {(p) => (
                        <input
                          {...p}
                          type="date"
                          value={s.checkIn}
                          onChange={(e) =>
                            setStays(
                              stays.map((x, j) =>
                                j === i ? { ...x, checkIn: e.target.value } : x,
                              ),
                            )
                          }
                        />
                      )}
                    </Field>
                    <Field label="Check-out (local date)">
                      {(p) => (
                        <input
                          {...p}
                          type="date"
                          value={s.checkOut}
                          onChange={(e) =>
                            setStays(
                              stays.map((x, j) =>
                                j === i ? { ...x, checkOut: e.target.value } : x,
                              ),
                            )
                          }
                        />
                      )}
                    </Field>
                  </div>
                  <Button
                    size="sm"
                    variant="ghost"
                    onClick={() => setStays(stays.filter((_, j) => j !== i))}
                  >
                    Remove stay {i + 1}
                  </Button>
                </fieldset>
              ))}
            </Card>
            <Card
              title="Ground transfers"
              actions={
                <Button
                  size="sm"
                  onClick={() =>
                    setTransfers((t) => [...t, { kind: 'AIRPORT_TO_HOTEL', city: '' }])
                  }
                >
                  Add transfer
                </Button>
              }
            >
              {transfers.length === 0 && (
                <p className="muted">
                  No transfers. A transfer follows the landing or precedes the departure in its
                  city.
                </p>
              )}
              {transfers.map((t, i) => (
                <fieldset className="fieldset" key={i}>
                  <legend>Transfer {i + 1}</legend>
                  <div className="form-grid">
                    <Field label="Kind">
                      {(p) => (
                        <select
                          {...p}
                          value={t.kind}
                          onChange={(e) =>
                            setTransfers(
                              transfers.map((x, j) =>
                                j === i
                                  ? { ...x, kind: e.target.value as TransferForm['kind'] }
                                  : x,
                              ),
                            )
                          }
                        >
                          <option value="AIRPORT_TO_HOTEL">Airport to hotel</option>
                          <option value="HOTEL_TO_AIRPORT">Hotel to airport</option>
                        </select>
                      )}
                    </Field>
                    <Field label="City (airport code)" error={errors[`xfer${i}`]}>
                      {(p) => (
                        <input
                          {...p}
                          value={t.city}
                          maxLength={3}
                          onChange={(e) =>
                            setTransfers(
                              transfers.map((x, j) =>
                                j === i ? { ...x, city: e.target.value.toUpperCase() } : x,
                              ),
                            )
                          }
                        />
                      )}
                    </Field>
                  </div>
                  <Button
                    size="sm"
                    variant="ghost"
                    onClick={() => setTransfers(transfers.filter((_, j) => j !== i))}
                  >
                    Remove transfer {i + 1}
                  </Button>
                </fieldset>
              ))}
            </Card>
          </>
        )}
        {capabilities.arrangeForOthers(session) && (
          <Card title="Arranging for someone else?">
            <Field
              label="Traveler employee id (optional)"
              hint="Leave empty to travel yourself. Managers and travel admins may arrange for others."
            >
              {(p) => (
                <input
                  {...p}
                  value={travelerId}
                  onChange={(e) => setTravelerId(e.target.value)}
                  placeholder="emp_1004"
                />
              )}
            </Field>
          </Card>
        )}
        <Alert tone="warn" role="status">
          <span id="submit-semantics">
            <strong>Submitting books the trip.</strong> The platform searches, applies policy and,
            when policy allows it without approval, books with the simulated suppliers
            automatically. When policy requires an approval, the trip waits for an approver. Nothing
            here is a preview.
          </span>
        </Alert>
        {err && (
          <Alert
            tone={err instanceof UncertainError ? 'warn' : 'danger'}
            title={
              err instanceof UncertainError
                ? 'The answer did not arrive.'
                : 'The request was refused.'
            }
          >
            {describe(err)}
            {err instanceof ApiError && err.problem.fields && (
              <ul>
                {Object.entries(err.problem.fields).map(([f, m]) => (
                  <li key={f}>
                    <code>{f}</code>: {m}
                  </li>
                ))}
              </ul>
            )}
            {err instanceof UncertainError &&
              ' Submitting again with the same details is safe: the platform recognises the request and returns the same trip.'}
          </Alert>
        )}
        <div className="row">
          <Button type="submit" variant="primary" busy={create.isPending}>
            {confirmed ? 'Confirm and submit' : 'Review and submit'}
          </Button>
          {confirmed && !create.isPending && (
            <span className="muted">Press again to submit; the platform may book immediately.</span>
          )}
        </div>
      </form>
    </>
  );
}
