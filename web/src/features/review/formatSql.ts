/** 主子句换行，AND/OR/ON 缩进一级。 */
const MAJOR =
  /\b(select|from|where|group by|order by|having|limit|offset|insert into|values|update|set|delete from|union all|union|left join|right join|inner join|outer join|cross join|join)\b/gi;
const MINOR = /\b(and|or|on)\b/gi;

/** 捕获组切分：奇数下标是字符串字面量，原样保留。 */
const LITERAL = /('(?:[^']|'')*')/;

/** 换行插在关键字前面，前一行末尾会留下原来的那个空格。 */
const TRAILING_SPACE = / +\n/g;

function layout(part: string): string {
  return part
    .replace(/\s+/g, ' ')
    .replace(MAJOR, (match) => `\n${match}`)
    .replace(MINOR, (match) => `\n  ${match}`);
}

/**
 * 展示用的 SQL 换行排版——把一行长语句摊成能读的形状，不改语义、不改大小写。
 *
 * 只排版字面量<em>之外</em>的片段，否则 `WHERE name = 'from A join B'` 会被从值里面切开。
 * ponytail: 纯正则，不引 SQL 格式化库；它只需要好读，不需要标准化。
 */
export function formatSql(sql: string): string {
  return sql
    .split(LITERAL)
    .map((part, index) => (index % 2 ? part : layout(part)))
    .join('')
    .replace(TRAILING_SPACE, '\n')
    .trim();
}
