import { httpClient, ApiError, TOKEN_STORAGE_KEY, type ApiResponse } from './httpClient';
import type { AuthUser } from '@/types/domain';

export interface LoginCredentials {
  email: string;
  password: string;
}

export interface LoginResult {
  success: boolean;
  message?: string;
  data?: { token: string; user?: AuthUser };
}

export const authApi = {
  login: async (credentials: LoginCredentials): Promise<LoginResult> => {
    try {
      const { data } = await httpClient.post<ApiResponse<{ token: string; user?: AuthUser }>>(
        '/auth/login',
        credentials,
      );
      return data;
    } catch (error) {
      // Invalid credentials render inline on the login form, not as a redirect.
      if (error instanceof ApiError && (error.status === 401 || error.status === 403)) {
        return { success: false, message: 'Invalid credentials' };
      }
      throw error;
    }
  },

  me: async (): Promise<ApiResponse<AuthUser>> => {
    try {
      const { data } = await httpClient.get<ApiResponse<AuthUser>>('/auth/me');
      return data;
    } catch (error) {
      localStorage.removeItem(TOKEN_STORAGE_KEY);
      throw new ApiError('Not authenticated', error instanceof ApiError ? error.status : undefined);
    }
  },

  logout: () => {
    localStorage.removeItem(TOKEN_STORAGE_KEY);
  },
};
