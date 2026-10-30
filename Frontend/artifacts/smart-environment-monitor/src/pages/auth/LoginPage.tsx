import { useState, type FormEvent } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { Activity, ArrowLeft, ArrowRight, Cpu, LockKeyhole, ShieldCheck } from 'lucide-react';
import { authApi } from '@/api/authApi';
import { TOKEN_STORAGE_KEY } from '@/api/httpClient';
import { useToast } from '@/hooks/use-toast';
import { useDocumentTheme } from '@/hooks/use-document-theme';

export function LoginPage() {
  useDocumentTheme('console');
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [isLoading, setIsLoading] = useState(false);
  const { toast } = useToast();
  const navigate = useNavigate();

  const handleLogin = async (e: FormEvent) => {
    e.preventDefault();
    setIsLoading(true);
    try {
      const res = await authApi.login({ email, password });
      if (res.success && res.data?.token) {
        localStorage.setItem(TOKEN_STORAGE_KEY, res.data.token);
        navigate('/admin/dashboard', { replace: true });
      } else {
        toast({ title: 'Login failed', description: res.message || 'Invalid credentials', variant: 'destructive' });
      }
    } catch (err) {
      const message = err instanceof Error ? err.message : 'Could not reach auth service';
      toast({ title: 'Error', description: message, variant: 'destructive' });
    } finally {
      setIsLoading(false);
    }
  };

  return (
    <div className="login-page">
      <section className="login-story">
        <Link to="/" className="brand">
          <span className="brand-mark"><Activity size={18} /></span>FIELD<span className="brand-light">/SENSE</span>
        </Link>
        <div className="login-statement">
          <div className="eyebrow">Operator console</div>
          <h1>Every station, every sensor, one console.</h1>
          <p>Fleet health, calibration, alerts and the AI engine's findings, for the people who keep the network running.</p>
        </div>
        <div className="login-facts">
          <span><Cpu size={16} aria-hidden="true" />Live telemetry</span>
          <span><ShieldCheck size={16} aria-hidden="true" />Calibration records</span>
          <span><LockKeyhole size={16} aria-hidden="true" />Role-based access</span>
        </div>
      </section>

      <div className="login-form-wrap">
        <form className="login-form" onSubmit={handleLogin}>
          <div>
            <div className="eyebrow">RESTRICTED ACCESS</div>
            <h2>Sign in to the console</h2>
          </div>
          <p>Use the operator account issued to you. The public observatory needs no sign-in.</p>
          <div className="field">
            <label htmlFor="login-email">Email address</label>
            <input
              id="login-email"
              type="email"
              value={email}
              onChange={(e) => setEmail(e.target.value)}
              required
              autoComplete="username"
              placeholder="operator@weatherplatform.local"
              data-testid="input-login-email"
            />
          </div>
          <div className="field">
            <label htmlFor="login-password">Password</label>
            <input
              id="login-password"
              type="password"
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              required
              autoComplete="current-password"
              data-testid="input-login-password"
            />
          </div>
          <button type="submit" className="button button-primary" disabled={isLoading} data-testid="button-login">
            {isLoading ? 'Signing in…' : 'Sign in safely'} <ArrowRight size={16} aria-hidden="true" />
          </button>
          <Link to="/" className="login-back"><ArrowLeft size={14} aria-hidden="true" /> Back to the public observatory</Link>
        </form>
      </div>
    </div>
  );
}
