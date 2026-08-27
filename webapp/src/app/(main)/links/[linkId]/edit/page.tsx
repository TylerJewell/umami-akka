import type { Metadata } from 'next';
import { LinkEditPage } from './LinkEditPage';

export default async function ({ params }: { params: Promise<{ linkId: string }> }) {
  const { linkId } = await params;

  return <LinkEditPage linkId={linkId} />;
}

export const metadata: Metadata = {
  title: 'Edit Link',
};
