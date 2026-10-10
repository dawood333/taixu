import { useState, type FormEvent } from "react";

interface LoginViewProps {
  initialToken: string;
  busy: boolean;
  error: string;
  onLogin: (token: string) => Promise<void>;
}

export function LoginView({ initialToken, busy, error, onLogin }: LoginViewProps) {
  const [token, setToken] = useState(initialToken);

  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const value = token.trim();
    if (value) void onLogin(value);
  }

  return (
    <main className="login-view">
      <section className="login-panel" aria-label="Connect to the TaiXu Agent Hub">
        <form className="login-form" onSubmit={submit}>
          <label htmlFor="token-input">TaiXu Agent Hub pairing code</label>
          <input
            id="token-input"
            name="token"
            type="password"
            autoComplete="current-password"
            value={token}
            disabled={busy}
            onChange={(event) => setToken(event.target.value)}
            required
          />
          <button className="primary-button" type="submit" disabled={busy}>
            {busy ? "Connecting…" : "Open workspace"}
          </button>
        </form>
        <p className="form-error" role="alert">{error}</p>
      </section>
    </main>
  );
}
