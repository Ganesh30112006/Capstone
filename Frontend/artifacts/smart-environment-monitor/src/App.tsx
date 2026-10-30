import { BrowserRouter } from 'react-router-dom';
import { AppProviders } from '@/providers/AppProviders';
import { AppRouter } from '@/router/AppRouter';

const basename = import.meta.env.BASE_URL.replace(/\/$/, '');

export default function App() {
  return (
    <AppProviders>
      <BrowserRouter basename={basename}>
        <AppRouter />
      </BrowserRouter>
    </AppProviders>
  );
}
