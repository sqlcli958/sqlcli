/**
 * 规则页。
 *
 * 只有两个固定分组，不做规则集的增删改：结构规则和 SQL 类规则在评估器里本来就不能混
 * （SQL 类规则拿不到 SQL 输入会让整次评估报错），所以「让用户自己建几个规则集」
 * 的结果永远是这两个。既然结果固定，就不该让人先想明白再开始配。
 *
 * 规则集的 id / 文件名 / 版本 / 匹配条件全部内部生成 —— 一个别名就一种数据库，
 * 匹配条件恒为真，那是规则集随程序发布、跨库复用时代的遗留。
 */
import { useEffect, useState } from 'react';
import { useMutation, useQuery } from '@tanstack/react-query';
import { useSearchParams } from 'react-router-dom';
import {
  createPolicyRuleSet, deletePolicyRuleSet, getPolicyRuleSets, setPolicyRuleSetBinding, updatePolicyRuleSet,
} from '../../api/policyRules';
import { getSession } from '../../api/session';
import { queryClient } from '../../api/queryClient';
import { useSessionStore } from '../../state/sessionStore';
import type { PolicyRuleDto, PolicyRuleSetDto } from '../../types/api';
import { ActionIcon } from '../../ui/ActionIcon';
import { RuleCatalog } from './RuleCatalog';
import { RuleForm } from './RuleForm';
import { CATEGORIES, CATEGORY_KEYS, newRule, type RulePhase } from './ruleSchema';
import './rules-page.css';
import { Button } from '../../ui/Button';

type View = 'catalog' | 'rule' | 'add-rule';

interface Group {
  fileName: string;
  id: string;
  title: string;
  phase: RulePhase;
  hint: string;
}

const GROUPS: Group[] = [
  {
    fileName: 'structure.yaml',
    id: 'structure-policy',
    title: '结构规范',
    phase: 'design',
    hint: '设计新表和审查 DDL 时执行（schema design review）',
  },
  {
    fileName: 'sql-migration.yaml',
    id: 'sql-migration-policy',
    title: 'SQL 与迁移',
    phase: 'sql',
    hint: '审查迁移脚本时执行（schema migration lint）',
  },
];

const MENU_ICON = 'M3 5h18v2H3V5zm0 6h18v2H3v-2zm0 6h12v2H3v-2z';
const BOOK_ICON = 'M4 4h11a3 3 0 013 3v13H7a3 3 0 00-3 3V4zm2 2v12.2A5 5 0 017 18h9V7a1 1 0 00-1-1H6z';

function Glyph({ path }: { path: string }) {
  return <svg width="14" height="14" viewBox="0 0 24 24" fill="currentColor" aria-hidden="true">
    <path d={path} />
  </svg>;
}

/** 该分组还没配的类别。同一类别可以有多条（比如多条 dialect_sql_pattern），这里只管首次补齐。 */
function missingCategories(group: Group, rules: PolicyRuleDto[]): string[] {
  const used = new Set(rules.map((rule) => rule.category));
  return CATEGORY_KEYS.filter((category) =>
    CATEGORIES[category].phase === group.phase && !used.has(category));
}

function emptySet(group: Group): PolicyRuleSetDto {
  return {
    kind: 'PolicyRuleSet', id: group.id, title: group.title, version: '1',
    match: {}, defaults: {}, sources: [], rules: [],
  };
}

export function RulesPage() {
  const [searchParams] = useSearchParams();
  const alias = searchParams.get('alias');
  const isConnected = useSessionStore((state) => state.isConnected);
  const setSession = useSessionStore((state) => state.setSession);

  const [collapsed, setCollapsed] = useState(false);
  const [view, setView] = useState<View>('catalog');
  const [activeFile, setActiveFile] = useState<string | null>(null);
  const [draft, setDraft] = useState<PolicyRuleSetDto | null>(null);
  const [ruleIndex, setRuleIndex] = useState(0);

  useEffect(() => {
    if (isConnected) return;
    getSession().then((session) =>
      setSession(session.alias, session.token ?? null, session.revision, session.readOnly, session.capabilities));
  }, [isConnected, setSession]);

  const rules = useQuery({
    queryKey: ['policy-rules', alias],
    queryFn: ({ signal }) => getPolicyRuleSets(signal),
    enabled: isConnected,
  });

  const stored = (group: Group) => rules.data?.ruleSets.find((item) => item.fileName === group.fileName);
  const activeGroup = GROUPS.find((group) => group.fileName === activeFile) ?? null;
  const activeStored = activeGroup ? stored(activeGroup) : undefined;

  const refresh = () => queryClient.invalidateQueries({ queryKey: ['policy-rules', alias] });
  const save = useMutation({
    mutationFn: () => {
      const payload = activeStored
        ? { ...draft!, version: String((Number(draft!.version) || 0) + 1) }
        : draft!;
      return activeStored
        ? updatePolicyRuleSet(activeFile!, payload)
        : createPolicyRuleSet(activeFile!, payload);
    },
    onSuccess: (saved) => { setDraft(saved.ruleSet); refresh(); },
  });
  const binding = useMutation({
    mutationFn: ({ name, enabled }: { name: string; enabled: boolean }) => setPolicyRuleSetBinding(name, enabled),
    onSuccess: refresh,
  });
  /** 分组固定，删文件只发生在删掉最后一条规则时——规则集至少要有一条规则才合法。 */
  const removeFile = useMutation({
    mutationFn: deletePolicyRuleSet,
    onSuccess: () => { setDraft(null); setActiveFile(null); setView('catalog'); refresh(); },
  });

  function select(group: Group, index: number | null) {
    const existing = stored(group)?.ruleSet;
    setActiveFile(group.fileName);
    // id / title 归分组管，旧文件里的值下次保存时一并规范化
    setDraft(existing
      ? { ...structuredClone(existing), id: group.id, title: group.title }
      : emptySet(group));
    setRuleIndex(index ?? 0);
    setView(index === null && !existing?.rules.length ? 'add-rule' : 'rule');
  }
  function patchRule(next: PolicyRuleDto) {
    setDraft((current) => current && ({
      ...current, rules: current.rules.map((rule, i) => (i === ruleIndex ? next : rule)),
    }));
  }
  function addRule(category: string) {
    if (!draft) return;
    setDraft({ ...draft, rules: [...draft.rules, newRule(category, draft.rules)] });
    setRuleIndex(draft.rules.length);
    setView('rule');
  }
  /** 一次把该分组所有还没加的类别补齐，每条带默认值，之后照常改照常删。 */
  function addAll(group: Group) {
    if (!draft) return;
    const rules = [...draft.rules];
    for (const category of missingCategories(group, draft.rules)) {
      rules.push(newRule(category, rules));
    }
    setDraft({ ...draft, rules });
    setRuleIndex(draft.rules.length);
    setView('rule');
  }
  /**
   * 删规则。
   *
   * 不要求先选中分组——列表里点哪条删哪条。删到一条不剩时删掉整个规则文件，
   * 因为 RuleSetLoader.validate 不接受空规则集。
   */
  function dropRule(group: Group, index: number) {
    const file = stored(group);
    const base = group.fileName === activeFile && draft ? draft : file?.ruleSet;
    if (!base) return;
    const rules = base.rules.filter((_, i) => i !== index);
    if (rules.length === 0) {
      if (!file) { setActiveFile(group.fileName); setDraft({ ...base, rules }); setView('add-rule'); return; }
      if (confirm(`删掉「${group.title}」的最后一条规则会清空整个分组，确定？`)) {
        removeFile.mutate(group.fileName);
      }
      return;
    }
    setActiveFile(group.fileName);
    setDraft({ ...base, id: group.id, title: group.title, rules });
    setRuleIndex((current) => {
      const next = group.fileName === activeFile ? current : 0;
      return next >= index && next > 0 ? next - 1 : Math.min(next, rules.length - 1);
    });
    setView('rule');
  }

  const dirty = draft != null
    && JSON.stringify(draft.rules) !== JSON.stringify(activeStored?.ruleSet.rules ?? []);

  return <div className={`rules-page${collapsed ? ' is-collapsed' : ''}`}>
    <aside className="rules-side">
      <div className="rules-side-head">
        <Button
          onClick={() => setCollapsed((value) => !value)}
          title={collapsed ? '展开规则列表' : '收起规则列表'}
          aria-label={collapsed ? '展开规则列表' : '收起规则列表'}
          aria-expanded={!collapsed}
          size="sm"
          icon
        >
          <Glyph path={MENU_ICON} />
        </Button>
      </div>

      {!collapsed && <div className="rules-tree">
        <button type="button" className={`rules-catalog-link${view === 'catalog' ? ' is-active' : ''}`}
          onClick={() => setView('catalog')}>
          <Glyph path={BOOK_ICON} />
          规则讲解
        </button>
        {rules.isError && <p className="rules-error" role="alert">无法读取规则：{rules.error.message}</p>}

        {GROUPS.map((group) => {
          const active = group.fileName === activeFile;
          const file = stored(group);
          const shown = active && draft ? draft : (file?.ruleSet ?? emptySet(group));
          const enabled = file?.enabled ?? false;
          const state = !file ? '还没有规则' : enabled ? '已启用校验' : '未启用，policy check 不会执行';
          return <div className="rules-set" key={group.fileName}>
            <div className={`rules-set-head${active ? ' is-active' : ''}`}>
              <button type="button" className="rules-set-name" onClick={() => select(group, null)}
                title={`${group.hint}。${state}`}>
                <span className="rules-dot" data-tone={!file ? 'off' : enabled ? 'ok' : 'warn'} />
                {group.title}
              </button>
              <ActionIcon action="add" label={`给「${group.title}」添加规则`}
                onClick={() => { select(group, null); setView('add-rule'); }} />
            </div>
            {shown.rules.length === 0
              ? <p className="rules-empty">还没有规则</p>
              : <ul className="rules-list">
                {shown.rules.map((rule, index) => <li key={rule.id}>
                  <button type="button"
                    className={active && view === 'rule' && ruleIndex === index ? 'is-active' : ''}
                    onClick={() => {
                      if (active) { setRuleIndex(index); setView('rule'); } else select(group, index);
                    }}>
                    <code>{rule.category}</code>
                    <small>{rule.title}</small>
                  </button>
                  <ActionIcon action="delete" label={`删除规则 ${rule.id}`}
                    onClick={() => dropRule(group, index)} />
                </li>)}
              </ul>}
          </div>;
        })}
      </div>}
    </aside>

    <main className="rules-main">
      {!isConnected && <p className="rules-error">正在连接图谱工作区…</p>}
      {view === 'catalog' && <RuleCatalog />}

      {view !== 'catalog' && draft && activeGroup && <>
        <div className="rules-bar">
          <strong>{activeGroup.title}</strong>
          {view === 'rule' && draft.rules[ruleIndex] && <>
            <span className="rules-sep">/</span>
            <code>{draft.rules[ruleIndex].id}</code>
          </>}
          <span className="rules-spacer" />
          <label className="rules-toggle" title={activeStored ? activeGroup.hint : '保存规则后才能启用'}>
            <input type="checkbox" checked={activeStored?.enabled ?? false}
              disabled={!activeStored || binding.isPending}
              onChange={(event) => binding.mutate({ name: activeFile!, enabled: event.target.checked })} />
            启用校验
          </label>
          <Button
            disabled={save.isPending || !dirty || draft.rules.length === 0}
            onClick={() => save.mutate()}
            variant="primary"
            size="sm"
          >{save.isPending ? '保存中…' : dirty ? '保存' : '已保存'}</Button>
        </div>
        {save.isError && <p className="rules-error" role="alert">保存失败：{save.error.message}</p>}
        {removeFile.isError && <p className="rules-error" role="alert">删除失败：{removeFile.error.message}</p>}

        {view === 'add-rule' && (() => {
          const missing = missingCategories(activeGroup, draft.rules);
          return <section className="rules-form">
            <span className="rf-block-title">添加规则<small>{activeGroup.hint}</small></span>
            {missing.length > 0 && <p className="rules-prefill">
              <Button onClick={() => addAll(activeGroup)} variant="primary">
                预填全部 {missing.length} 条
              </Button>
              每条带一套默认值，加进来之后照常改、照常删。
            </p>}
            <div className="rules-picker">
              {CATEGORY_KEYS.filter((category) => CATEGORIES[category].phase === activeGroup.phase)
                .map((category) => <button type="button" key={category} className="rules-pick"
                  onClick={() => addRule(category)}>
                  <code>{category}</code>
                  <span>{CATEGORIES[category].label}</span>
                  {!missing.includes(category) && <small>已有一条，可再加</small>}
                </button>)}
            </div>
          </section>;
        })()}

        {view === 'rule' && draft.rules[ruleIndex] && <section className="rules-form">
          <RuleForm rule={draft.rules[ruleIndex]} onChange={patchRule} />
        </section>}
      </>}
    </main>
  </div>;
}
