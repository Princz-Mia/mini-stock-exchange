import { zodResolver } from '@hookform/resolvers/zod';
import { useForm } from 'react-hook-form';
import { Navigate, useLocation, useNavigate } from 'react-router';
import { z } from 'zod';

import { ApiError } from '../api/client';
import { useLogin, useSession } from './session';

const Credentials = z.object({
  username: z.string().trim().min(1, 'Enter your username'),
  password: z.string().min(1, 'Enter your password'),
});
type Credentials = z.infer<typeof Credentials>;

function loginError(error: unknown): string {
  if (error instanceof ApiError && error.code === 'INVALID_CREDENTIALS') {
    return 'Wrong username or password.';
  }
  if (error instanceof ApiError && error.code === 'LOGIN_RATE_LIMITED') {
    // The server's message says how long to wait.
    return error.message;
  }
  return 'Could not sign in. Try again in a moment.';
}

/** Typed input stays in the form after a failed attempt, so the user only fixes what was wrong. */
export function LoginPage() {
  const session = useSession();
  const login = useLogin();
  const navigate = useNavigate();
  const location = useLocation();
  const from = (location.state as { from?: string } | null)?.from ?? '/terminal';
  const {
    register,
    handleSubmit,
    formState: { errors },
  } = useForm<Credentials>({ resolver: zodResolver(Credentials), defaultValues: { username: '', password: '' } });

  if (session.data) {
    return <Navigate to={from} replace />;
  }

  const submit = handleSubmit((credentials) =>
    login.mutate(credentials, { onSuccess: () => void navigate(from, { replace: true }) }),
  );

  return (
    <main className="login">
      <section className="panel login-card" aria-labelledby="login-title">
        <h1 id="login-title">Sign in</h1>
        <p className="muted">Trade with virtual funds in a simulated market.</p>
        <form onSubmit={(event) => void submit(event)} noValidate>
          <label htmlFor="username">Username</label>
          <input
            id="username"
            autoComplete="username"
            aria-invalid={errors.username !== undefined}
            {...register('username')}
          />
          {errors.username !== undefined && <p className="field-error">{errors.username.message}</p>}
          <label htmlFor="password">Password</label>
          <input
            id="password"
            type="password"
            autoComplete="current-password"
            aria-invalid={errors.password !== undefined}
            {...register('password')}
          />
          {errors.password !== undefined && <p className="field-error">{errors.password.message}</p>}
          {login.isError && (
            <p role="alert" className="form-error">
              {loginError(login.error)}
            </p>
          )}
          <button type="submit" className="primary" disabled={login.isPending}>
            {login.isPending ? 'Signing in…' : 'Sign in'}
          </button>
        </form>
      </section>
    </main>
  );
}
