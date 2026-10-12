import pytest

from benchmark_fixture import first_real_chapter


def test_chapter_fixture_skips_table_of_contents():
    chapter = 'CHAPTER I\n' + 'The rabbit checked a watch. ' * 100
    source = 'CHAPTER I\nDown the Rabbit-Hole\n\nCHAPTER II\nThe Pool of Tears\n\n' + chapter + '\nCHAPTER II\n' + 'Another real chapter. ' * 100
    assert first_real_chapter(source).strip() == chapter.strip()


def test_chapter_fixture_refuses_to_benchmark_a_contents_entry():
    with pytest.raises(ValueError, match='No complete chapter'):
        first_real_chapter('CHAPTER I\nDown the Rabbit-Hole\n')
