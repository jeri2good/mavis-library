from sources import chapter_chunks


def first_real_chapter(text):
    for _, chapter in chapter_chunks(text):
        if len(chapter.strip()) >= 2000:
            return chapter
    raise ValueError('No complete chapter found for the benchmark')
