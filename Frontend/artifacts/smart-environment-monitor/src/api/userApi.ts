import { httpClient, type ApiResponse } from './httpClient';
import type { AuthUser, UserRole } from '@/types/domain';

export const userApi = {
  /**
   * Does not catch. An account list that failed to load used to render as
   * "No accounts were returned", which on an access-control page reads as a
   * statement about who has access rather than a failure to find out.
   */
  list: async (role?: UserRole): Promise<AuthUser[]> => {
    const { data } = await httpClient.get<ApiResponse<AuthUser[]>>('/users', { params: role ? { role } : undefined });
    return data.data ?? [];
  },

  /** The backend refuses to demote the last remaining admin, or the caller themselves. */
  updateRole: async (userId: number, role: UserRole): Promise<AuthUser> => {
    const { data } = await httpClient.patch<ApiResponse<AuthUser>>(`/users/${userId}/role`, { role });
    return data.data;
  },

  remove: async (userId: number): Promise<void> => {
    await httpClient.delete(`/users/${userId}`);
  },
};
