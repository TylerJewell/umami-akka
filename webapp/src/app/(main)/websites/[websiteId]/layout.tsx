import type { Metadata } from 'next';
import { WebsiteLayout } from '@/app/(main)/websites/[websiteId]/WebsiteLayout';

export default async function ({
  children,
  modal,
  params,
}: {
  children: any;
  modal: React.ReactNode;
  params: Promise<{ websiteId: string }>;
}) {
  const { websiteId } = await params;

  return (
    <WebsiteLayout websiteId={websiteId}>
      {children}
      {modal}
    </WebsiteLayout>
  );
}

export const metadata: Metadata = {
  title: {
    template: '%s | Umami',
    default: 'Websites | Umami',
  },
};
