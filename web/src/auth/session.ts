import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';

import { ApiError, api } from '../api/client';
import type { Identity } from '../api/types';

const ME = ['me'] as const;

/** The logged-in user, or null when there is no session. Asked once; login and logout update it directly. */
export function useSession() {
  return useQuery({
    queryKey: ME,
    queryFn: async (): Promise<Identity | null> => {
      try {
        return await api.me();
      } catch (error) {
        if (error instanceof ApiError && error.status === 401) {
          return null;
        }
        throw error;
      }
    },
    staleTime: Infinity,
    retry: false,
  });
}

export function useLogin() {
  const client = useQueryClient();
  return useMutation({
    mutationFn: ({ username, password }: { username: string; password: string }) => api.login(username, password),
    onSuccess: (identity) => client.setQueryData(ME, identity),
  });
}

/** Clears every cached query, so nothing from this session is shown to the next one. */
export function useLogout() {
  const client = useQueryClient();
  return useMutation({
    mutationFn: () => api.logout(),
    onSettled: () => {
      client.clear();
      client.setQueryData(ME, null);
    },
  });
}

export function isTrader(identity: Identity | null | undefined): boolean {
  return identity?.roles.includes('TRADER') ?? false;
}

export function isOperator(identity: Identity | null | undefined): boolean {
  return identity?.roles.includes('OPERATOR') ?? false;
}
