import { Link } from 'react-router-dom';
import { Activity, ArrowRight } from 'lucide-react';

export function UtilityPage({ code, title, description }: { code: string; title: string; description: string }) {
  return (
    <div className="utility-page">
      <Link to="/" className="brand" data-testid="link-utility-home">
        <span className="brand-mark"><Activity size={17} /></span>FIELD<span className="brand-light">/SENSE</span>
      </Link>
      <div className="utility-content">
        <div className="utility-code">{code}</div>
        <h1>{title}</h1>
        <p>{description}</p>
        <Link to="/" className="button button-primary" data-testid="button-utility-home">Return to observatory <ArrowRight size={14} /></Link>
      </div>
    </div>
  );
}

export function ForbiddenPage() {
  return (
    <UtilityPage
      code="403"
      title="This reading is restricted."
      description="You do not have permission to view this station record. Return to the public observatory or sign in as an operator."
    />
  );
}

export function NotFoundPage() {
  return (
    <UtilityPage
      code="404"
      title="That signal is not here."
      description="The page you requested has moved, or the address was entered incorrectly."
    />
  );
}
