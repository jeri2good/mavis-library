// The curated shelves shown when the app opens, on the web and in the Android app.
// One list, served by /api/shelves, so both apps always show the same selection.
// Only Project Gutenberg ids (public domain in the USA). Titles and covers are loaded live
// from the catalog, never written here. Every id below was checked against the live catalog.
//
// Shelves are ordered the way the home screen shows them. `kids: true` shelves also open in Kids mode.

export const SHELVES = [
  {
    id: 'popular', label: 'Most-loved classics', blurb: 'The books people reach for first.',
    ids: [1342, 84, 2701, 11, 1661, 64317, 345, 1260, 174, 98, 768, 1184, 158, 120, 1400, 2554],
  },
  {
    id: 'kids', label: 'Children’s favourites', blurb: 'Read-aloud stories and first chapter books.', kids: true,
    ids: [11, 55, 16, 113, 67098, 289, 236, 271, 1448, 45, 14838, 500, 12, 778, 1874, 11757, 501, 2781, 1018, 74],
  },
  {
    id: 'fairy', label: 'Fairy tales & fables', blurb: 'Old stories, still the best bedtime ones.', kids: true,
    ids: [2591, 1597, 11339, 503, 14241, 2781, 500, 10607],
  },
  {
    id: 'science', label: 'Science, in the scientists’ own words', blurb: 'Darwin, Einstein, Newton, Faraday and friends.',
    ids: [1228, 5001, 14474, 33504, 2300, 3704, 28233, 14565, 8700, 28613, 201],
  },
  {
    id: 'nature', label: 'Animals & the natural world', blurb: 'Insects, bees, wild places and the creatures in them.', kids: true,
    ids: [67000, 3422, 4511, 38516, 38013, 236, 271, 2781],
  },
  {
    id: 'space', label: 'Stars & space', blurb: 'Astronomy for beginners, and the first space stories.',
    ids: [27378, 15620, 1013, 28918, 36, 35],
  },
  {
    id: 'tinker', label: 'Tinker & build', blurb: 'Boy Mechanic projects, electricity, radio, early flight — the original how-to magazines’ spirit.',
    ids: [12655, 71856, 22298, 22766, 63207, 6934, 14664, 28553, 67672, 25420, 820, 50523, 46232, 39272],
  },
  {
    id: 'magazines', label: 'Old magazines', blurb: 'Popular Science Monthly and other period magazines.',
    ids: [45361, 47296, 47024, 25093, 22242],
  },
  {
    id: 'maths', label: 'Maths & puzzles', blurb: 'Amusements, Euclid, and the ideas behind computing.',
    ids: [16713, 21076, 38769, 22599, 52052, 201],
  },
  {
    id: 'scifi', label: 'Science fiction, where it began', blurb: 'The machines, rockets and robots that started it all.',
    ids: [35, 36, 1013, 28918, 164, 19513, 103, 1268, 139, 551, 20782, 22541],
  },
  {
    id: 'kitchen', label: 'Home, kitchen & garden', blurb: 'Cooking, gardening and household know-how.',
    ids: [65061, 10136, 13923, 12238, 29728, 71395, 54138, 25278, 21682, 28845, 10766, 26209],
  },
];

export const TOPIC_CHIPS = [
  { topic: 'juvenile', label: 'For young readers' },
  { topic: 'fairy tales', label: 'Fairy tales' },
  { topic: 'animals', label: 'Animals' },
  { topic: 'science', label: 'Science' },
  { topic: 'astronomy', label: 'Astronomy' },
  { topic: 'chemistry', label: 'Chemistry' },
  { topic: 'engineering', label: 'Engineering' },
  { topic: 'electricity', label: 'Electricity' },
  { topic: 'mathematics', label: 'Mathematics' },
  { topic: 'exploration', label: 'Exploration' },
  { topic: 'science fiction', label: 'Science fiction' },
  { topic: 'adventure', label: 'Adventure' },
  { topic: 'detective', label: 'Mystery' },
  { topic: 'poetry', label: 'Poetry' },
  { topic: 'philosophy', label: 'Philosophy' },
  { topic: 'cooking', label: 'Cooking' },
];

/** Gutenberg's ids endpoint takes at most 24 ids per request. */
export const IDS_PER_REQUEST = 24;
