import type { Metadata } from 'next';
import { PixelEditPage } from './PixelEditPage';

export default async function ({ params }: { params: Promise<{ pixelId: string }> }) {
  const { pixelId } = await params;

  return <PixelEditPage pixelId={pixelId} />;
}

export const metadata: Metadata = {
  title: 'Edit Pixel',
};
