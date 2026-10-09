// Title-specific links to authorized lending and retail services. Mavis does
// not sign in to these services, sync loans, read protected files, or take
// payment. Each link opens the provider's own site or app.

function query(book) {
  const author = (book.authors || [])[0] || '';
  return `${book.title}${author ? ` ${author}` : ''}`.trim();
}

export function lendingLinks(book) {
  const q = encodeURIComponent(query(book));
  return [
    {
      id: 'libby',
      name: 'Libby by OverDrive',
      url: `https://www.overdrive.com/search?q=${q}`,
      cta: 'Check libraries on OverDrive',
      handles: 'Your library card, holds, loans, returns, and protected reading all happen in Libby or your library’s OverDrive site.',
      secondary: { label: 'Open Libby', url: 'https://libbyapp.com/' },
    },
    {
      id: 'hoopla',
      name: 'hoopla',
      url: `https://www.hoopladigital.com/search?q=${q}&scope=everything&type=direct`,
      cta: 'Search hoopla',
      handles: 'hoopla manages your library card, borrows, and protected reading in its own app or website.',
    },
  ];
}

export const LIBRARY_FINDERS = [
  { name: 'Find a library that uses Libby / OverDrive', url: 'https://www.overdrive.com/libraries' },
  { name: 'See if your library offers hoopla', url: 'https://www.hoopladigital.com/' },
];

export function retailLinks(book) {
  const q = encodeURIComponent(query(book));
  const isbn = book.isbn && /^97[89]\d{10}$/.test(book.isbn) ? book.isbn : null;
  return [
    {
      id: 'kobo',
      name: 'Kobo',
      url: `https://www.kobo.com/us/en/search?query=${isbn || q}`,
      cta: 'Search Kobo',
      note: 'Checkout and reading happen on Kobo. Purchased books usually carry DRM and open in Kobo apps or devices, not in Mavis.',
    },
    {
      id: 'google',
      name: 'Google Play Books',
      url: `https://play.google.com/store/search?q=${isbn || q}&c=books`,
      cta: 'Search Google Play Books',
      note: 'Checkout and reading happen in Google Play Books. Some purchases can be exported as DRM-free EPUB if the publisher allows it.',
    },
  ];
}
