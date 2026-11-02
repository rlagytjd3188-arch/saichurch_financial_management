import FinanceApp from '../components/FinanceApp';
export const dynamic='force-dynamic';
export default function Page(){return <FinanceApp viewer={process.env.APP_MODE==='viewer'}/>;}
