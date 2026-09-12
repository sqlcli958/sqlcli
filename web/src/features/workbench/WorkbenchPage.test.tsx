import { MemoryRouter } from 'react-router-dom';
import { render, screen } from '@testing-library/react';
import { describe, expect, test } from 'vitest';
import { CompletenessSection, coverageTone } from './WorkbenchPage';
import type { WorkspaceCompletenessDto } from '../../types/api';

describe('coverageTone 三态阈值', () => {
  test('覆盖率 >= 80% 是 ok，>= 40% 是 warn，否则 bad', () => {
    expect(coverageTone(9, 10)).toBe('ok');
    expect(coverageTone(8, 10)).toBe('ok');
    expect(coverageTone(4, 10)).toBe('warn');
    expect(coverageTone(3, 10)).toBe('bad');
    expect(coverageTone(0, 10)).toBe('bad');
  });

  test('没有分母时状态未知，算 warn，不除零报错', () => {
    expect(coverageTone(undefined, 0)).toBe('warn');
    expect(coverageTone(undefined, undefined)).toBe('warn');
    expect(coverageTone(0, 0)).toBe('warn');
  });
});

function renderSection(data?: WorkspaceCompletenessDto) {
  return render(
    <MemoryRouter>
      <CompletenessSection alias="demo" data={data} />
    </MemoryRouter>,
  );
}

const fullData: WorkspaceCompletenessDto = {
  tables: { total: 10, withComment: 9, withBusinessName: 4 },
  columns: { total: 40, withComment: 30, withBusinessName: 10, withSemanticType: 5, withValueHints: 2 },
  backlog: {
    candidateTables: 2,
    candidateRelations: 1,
    candidateTerms: 1,
    ignoredTables: 0,
    ignoredRelations: 1,
    ignoredTerms: 0,
    openIssuesBySeverity: { error: 3, warning: 5 },
    ignoredIssues: 1,
    unverifiedColumnSemantics: 7,
  },
  relations: { totalTables: 10, isolatedTables: 2, fkOnlyTables: 3 },
};

test('有数据时覆盖率、待办、关系健康都渲染出正确的数字', () => {
  renderSection(fullData);

  expect(screen.getByText('9/10（90%）')).toBeTruthy();
  expect(screen.getByText('4/10（40%）')).toBeTruthy();
  expect(screen.getByText('4')).toBeTruthy(); // 候选待评审 2+1+1
  expect(screen.getByText('7')).toBeTruthy(); // 字段语义待确认
  expect(screen.getByText(/2 张表没有任何关系/)).toBeTruthy();
  expect(screen.getByText(/3 张表只有数据库外键/)).toBeTruthy();
});

test('零数据（未导入图谱前）不崩，覆盖率显示 0/0', () => {
  renderSection(undefined);

  expect(screen.getAllByText('0/0（0%）').length).toBeGreaterThan(0);
  expect(screen.getByText('每张表至少有一条关系')).toBeTruthy();
});
