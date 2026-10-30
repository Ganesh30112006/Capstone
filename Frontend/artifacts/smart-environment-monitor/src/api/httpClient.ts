import axios, { AxiosError } from 'axios';

/**
 * The router mounts under `basename`, so a redirect written as an absolute path
 * leaves the application whenever the app is not served from the site root —
 * landing on the web server's own 404 instead of the app's Forbidden page. This
 * keeps the two in step.
 */
const ROUTER_BASE = import.meta.env.BASE_URL.replace(/\/$/, '');

function appPath(path: string): string {
  return `${ROUTER_BASE}${path}`;
}

/**
 * All requests go through the API Gateway. This is the only base URL the
 * frontend ever talks to — no service is addressed directly, and the browser
 * never talks to a device.
 *
 * Read from the build environment, because Vite inlines this at build time:
 * hardcoding it meant the only bundle that could ever be produced was one that
 * talks to the developer's own machine, so any deployment beyond localhost
 * required editing source. The fallback keeps `pnpm dev` working with no setup.
 */
export const API_BASE_URL =
  import.meta.env.VITE_API_BASE_URL ?? 'http://localhost:8443/api';

export const TOKEN_STORAGE_KEY = 'auth-token';

export const httpClient = axios.create({
  baseURL: API_BASE_URL,
  headers: { 'Content-Type': 'application/json' },
});

httpClient.interceptors.request.use((config) => {
  const token = localStorage.getItem(TOKEN_STORAGE_KEY);
  if (token) {
    // Never logged: interceptors here only attach the header, nothing prints it.
    config.headers.Authorization = `Bearer ${token}`;
  }
  return config;
});

httpClient.interceptors.response.use(
  (response) => response,
  (error: AxiosError) => {
    const status = error.response?.status;

    if (status === 401) {
      localStorage.removeItem(TOKEN_STORAGE_KEY);
      const path = window.location.pathname;
      if (path.startsWith(appPath('/admin')) && path !== appPath('/admin/login')) {
        window.location.href = appPath('/admin/login');
      }
      return Promise.reject(new ApiError('Authentication expired', status));
    }
    if (status === 403) {
      window.location.href = appPath('/403');
      return Promise.reject(new ApiError('Forbidden access', status));
    }
    if (status === 429) {
      window.dispatchEvent(new CustomEvent('api:rate-limit'));
      return Promise.reject(new ApiError('Too many requests. Please try again shortly.', status));
    }
    if (status === 400 || status === 409 || status === 422) {
      const message = extractMessage(error) ?? 'The request could not be processed.';
      return Promise.reject(new ApiError(message, status));
    }
    if (status === 404) {
      return Promise.reject(new ApiError('Not found', status));
    }
    if (status && status >= 500) {
      // Never surface a backend stack trace; a generic message only.
      return Promise.reject(new ApiError('The service is temporarily unavailable. Please try again shortly.', status));
    }
    if (!error.response) {
      return Promise.reject(new ApiError('Could not reach the API Gateway.', undefined));
    }
    return Promise.reject(new ApiError(extractMessage(error) ?? error.message, status));
  },
);

export class ApiError extends Error {
  status?: number;
  constructor(message: string, status?: number) {
    super(message);
    this.name = 'ApiError';
    this.status = status;
  }
}

function extractMessage(error: AxiosError): string | undefined {
  const data = error.response?.data as { message?: string } | undefined;
  return data?.message;
}

/** Generic envelope every backend service wraps its responses in. */
export interface ApiResponse<T> {
  success: boolean;
  status?: number;
  message?: string;
  data: T;
  path?: string;
}

/**
 * The only failure that legitimately means "there is nothing there".
 *
 * A 404 is an answer: the gateway reached the service, and the service says the
 * resource does not exist. Every other failure — a 500, a timeout, an
 * unreachable gateway, a rejected token — leaves the question unanswered, and
 * an unanswered question must never be rendered as an empty one. Swallowing
 * those turned "we could not ask" into "we asked, and all is well", which is
 * how an unreachable backend came to be drawn as a healthy, empty console.
 *
 * Use as `catch (error) { return absentAsNull(error); }` — it returns null for
 * a genuine absence and rethrows anything it cannot account for, so react-query
 * still sees the error and the page can say so.
 */
export function absentAsNull(error: unknown): null {
  if (error instanceof ApiError && error.status === 404) {
    return null;
  }
  throw error;
}
