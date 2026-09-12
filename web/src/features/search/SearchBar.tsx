import { useState, useCallback, useRef, useEffect } from 'react';
import { useQuery } from '@tanstack/react-query';
import { search } from '../../api/search';
import type { SearchHitDto } from '../../types/api';

interface SearchBarProps {
  onSelectTable: (schema: string, table: string) => void;
  onSelectColumn: (schema: string, table: string, column: string) => void;
}

export function SearchBar({ onSelectTable, onSelectColumn }: SearchBarProps) {
  const [query, setQuery] = useState('');
  const [debouncedQuery, setDebouncedQuery] = useState('');
  const [showResults, setShowResults] = useState(false);
  const debounceRef = useRef<ReturnType<typeof setTimeout>>();

  // Debounce input by 300ms
  useEffect(() => {
    if (debounceRef.current) {
      clearTimeout(debounceRef.current);
    }
    debounceRef.current = setTimeout(() => {
      setDebouncedQuery(query);
    }, 300);

    return () => {
      if (debounceRef.current) {
        clearTimeout(debounceRef.current);
      }
    };
  }, [query]);

  const { data: searchResult, isLoading } = useQuery({
    queryKey: ['search', debouncedQuery],
    queryFn: ({ signal }) => search({ q: debouncedQuery, type: 'table,column,term', limit: 20 }, signal),
    enabled: debouncedQuery.length >= 1,
    staleTime: 30_000,
  });

  const handleInputChange = useCallback((e: React.ChangeEvent<HTMLInputElement>) => {
    setQuery(e.target.value);
    setShowResults(true);
  }, []);

  const handleSelect = useCallback(
    (hit: SearchHitDto) => {
      if (hit.type === 'column' && hit.column) {
        onSelectColumn(hit.schema, hit.table, hit.column);
      } else if (hit.table) {
        // term 命中带回来的是它指向的那个对象的坐标（后端 termTarget），
        // 所以这里和 table 走同一条路：搜业务词的人要的就是落到那张表。
        onSelectTable(hit.schema, hit.table);
      }
      setShowResults(false);
      setQuery('');
    },
    [onSelectTable, onSelectColumn],
  );

  const handleBlur = useCallback(() => {
    // Delay to allow click on results
    setTimeout(() => setShowResults(false), 200);
  }, []);

  const handleFocus = useCallback(() => {
    if (query.length > 0) setShowResults(true);
  }, [query]);

  const results = searchResult?.results ?? [];
  const indexStatus = searchResult?.indexStatus;
  const total = searchResult?.total ?? 0;

  return (
    <div className="search-bar">
      <div className="search-input-wrapper">
        <svg className="search-icon" viewBox="0 0 24 24" width="16" height="16">
          <path
            fill="currentColor"
            d="M15.5 14h-.79l-.28-.27A6.471 6.471 0 0 0 16 9.5 6.5 6.5 0 1 0 9.5 16c1.61 0 3.09-.59 4.23-1.57l.27.28v.79l5 4.99L20.49 19l-4.99-5zm-6 0C7.01 14 5 11.99 5 9.5S7.01 5 9.5 5 14 7.01 14 9.5 11.99 14 9.5 14z"
          />
        </svg>
        <input
          type="text"
          className="search-input"
          placeholder="搜索表名、字段或业务术语…"
          value={query}
          onChange={handleInputChange}
          onBlur={handleBlur}
          onFocus={handleFocus}
        />
        {isLoading && <span className="search-loading">...</span>}
      </div>
      {showResults && results.length > 0 && (
        <div className="search-results-dropdown">
          {indexStatus && indexStatus !== 'ready' && (
            <div className="search-index-warning" title="搜索索引未就绪，结果来自实时扫描">
              索引未就绪
            </div>
          )}
          {results.map((hit, idx) => (
            <button
              key={`${hit.type}-${hit.schema}-${hit.table}-${hit.column || ''}-${idx}`}
              className="search-result-item"
              onMouseDown={() => handleSelect(hit)}
              title={[hit.description && `业务描述：${hit.description}`, hit.comment && `注释：${hit.comment}`]
                .filter(Boolean)
                .join('\n') || undefined}
            >
              <span className={`search-result-type search-result-type-${hit.type}`}>
                {hit.type}
              </span>
              <span className="search-result-label">{hit.name}</span>
              {(hit.businessName || hit.description || hit.comment) && (
                <span className="search-result-detail">
                  {hit.businessName ?? hit.description ?? hit.comment}
                </span>
              )}
              {hit.semanticType && (
                <span className="search-result-semantic">{hit.semanticType}</span>
              )}
              {hit.candidate && <span className="search-result-candidate">候选</span>}
            </button>
          ))}
          {total > results.length && (
            <div className="search-results-more">
              还有 {total - results.length} 条结果…
            </div>
          )}
        </div>
      )}
    </div>
  );
}
