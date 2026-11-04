import { useNavigate } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { EmptyState, PageHeader } from '../components/ui';
import { Button } from '../components/Button';

/**
 * 未匹配到路由时的兜底页面。
 *
 * 之前未知路径会被静默重定向到仪表盘：用户点了一个坏链接，界面却"成功"
 * 打开了一个毫不相关的页面，地址栏也跟着变掉，既没有解释也回不到他本来
 * 想去的地方。这里保留侧边导航，并明确告诉用户地址不存在。
 */
export function NotFound() {
  const { t } = useTranslation();
  const navigate = useNavigate();

  return (
    <div>
      <PageHeader title={t('notFound.title')} description={t('notFound.subtitle')} />
      <EmptyState>
        <p>{t('notFound.description')}</p>
        <Button variant="primary" onClick={() => navigate('/dashboard')}>
          {t('notFound.backToDashboard')}
        </Button>
      </EmptyState>
    </div>
  );
}
