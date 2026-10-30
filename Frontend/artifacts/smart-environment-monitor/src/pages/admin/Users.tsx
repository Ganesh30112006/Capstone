import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { ShieldCheck, Trash2, UserRound } from 'lucide-react';
import { userApi } from '@/api/userApi';
import { authApi } from '@/api/authApi';
import { AdminPageHeader } from '@/components/PageHeaders';
import { Badge } from '@/components/StatusIndicators';
import { ConfirmDialog } from '@/components/ConfirmDialog';
import { DataState, derived } from '@/components/DataState';
import { useToast } from '@/hooks/use-toast';
import type { AuthUser, UserRole } from '@/types/domain';

export function Users() {
  const { toast } = useToast();
  const queryClient = useQueryClient();
  const usersQuery = useQuery({ queryKey: ['users'], queryFn: () => userApi.list() });
  const users: AuthUser[] = usersQuery.data ?? [];
  const { data: me } = useQuery({ queryKey: ['me'], queryFn: authApi.me, retry: false });
  const [pendingDelete, setPendingDelete] = useState<AuthUser | null>(null);
  const [pendingRole, setPendingRole] = useState<{ user: AuthUser; role: UserRole } | null>(null);

  const currentUserId = me?.data?.id;
  const adminCount = users.filter((u) => u.role === 'ADMIN').length;

  const changeRole = useMutation({
    mutationFn: ({ userId, role }: { userId: number; role: UserRole }) => userApi.updateRole(userId, role),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['users'] });
      toast({ title: 'Role updated' });
    },
    onError: (error: Error) => toast({ title: 'Could not change the role', description: error.message, variant: 'destructive' }),
  });

  const removeUser = useMutation({
    mutationFn: (userId: number) => userApi.remove(userId),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['users'] });
      toast({ title: 'Account removed' });
    },
    onError: (error: Error) => toast({ title: 'Could not remove the account', description: error.message, variant: 'destructive' }),
  });

  return (
    <>
      <AdminPageHeader
        eyebrow="ACCESS CONTROL · USERS"
        title="Who can open this console."
        description="Accounts and their roles. An administrator cannot demote or delete their own account, and the platform always keeps at least one administrator."
      />

      <div className="panel">
        <div className="panel-heading">
          <div><div className="eyebrow">ACCOUNTS</div><h3>{derived(usersQuery, (u) => u.length)} registered</h3></div>
          <Badge tone="info">{derived(usersQuery, (u) => u.filter((x) => x.role === 'ADMIN').length)} administrator{adminCount === 1 ? '' : 's'}</Badge>
        </div>

        {/*
          An account list that failed to load used to render "No accounts were
          returned" — on an access-control page, a statement about who has
          access rather than an admission of not knowing.
        */}
        <DataState
          query={usersQuery}
          what="the account list"
          empty={<div className="empty-inline">No accounts are registered on this deployment.</div>}
        >
          {(loaded) => loaded.map((user) => {
          const isSelf = user.id === currentUserId;
          const isLastAdmin = user.role === 'ADMIN' && adminCount === 1;
          return (
            <div className="simple-row" key={user.id} data-testid={`row-user-${user.id}`}>
              <span className="simple-row-index">{user.role === 'ADMIN' ? <ShieldCheck size={15} /> : <UserRound size={15} />}</span>
              <span>
                <strong>{user.name}{isSelf && ' (you)'}</strong>
                <small>{user.email}</small>
              </span>
              <Badge tone={user.role === 'ADMIN' ? 'good' : 'neutral'}>{user.role}</Badge>
              <button
                className="button button-secondary"
                disabled={isSelf || isLastAdmin || changeRole.isPending}
                onClick={() => setPendingRole({ user, role: user.role === 'ADMIN' ? 'USER' : 'ADMIN' })}
                data-testid={`button-role-${user.id}`}
              >
                {user.role === 'ADMIN' ? 'Demote to user' : 'Promote to admin'}
              </button>
              <button
                className="table-more"
                disabled={isSelf || isLastAdmin || removeUser.isPending}
                onClick={() => setPendingDelete(user)}
                data-testid={`button-delete-user-${user.id}`}
                title={isSelf ? 'You cannot delete your own account' : 'Remove account'}
              >
                <Trash2 size={15} />
              </button>
            </div>
          );
          })}
        </DataState>
      </div>

      <ConfirmDialog
        open={pendingRole !== null}
        onOpenChange={(open) => !open && setPendingRole(null)}
        title={pendingRole ? `Change ${pendingRole.user.name} to ${pendingRole.role}?` : ''}
        description={
          pendingRole?.role === 'ADMIN'
            ? 'Administrators can command hardware, queue firmware, and manage other accounts.'
            : 'This account will lose access to the operator console.'
        }
        confirmLabel="Change role"
        onConfirm={() => {
          const request = pendingRole;
          setPendingRole(null);
          if (request) changeRole.mutate({ userId: request.user.id, role: request.role });
        }}
      />

      <ConfirmDialog
        open={pendingDelete !== null}
        onOpenChange={(open) => !open && setPendingDelete(null)}
        title={pendingDelete ? `Remove ${pendingDelete.name}?` : ''}
        description="The account will be deleted and the person will lose access immediately. This cannot be undone from the console."
        confirmLabel="Remove account"
        destructive
        onConfirm={() => {
          const user = pendingDelete;
          setPendingDelete(null);
          if (user) removeUser.mutate(user.id);
        }}
      />
    </>
  );
}
