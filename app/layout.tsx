import type {Metadata} from 'next';
import './globals.css';
export const metadata:Metadata={title:'새이음 재정 | 교회 재정관리',description:'하나의 원장으로 연결하는 교회 재정'};
export default function Layout({children}:{children:React.ReactNode}){return <html lang="ko"><body>{children}</body></html>;}
