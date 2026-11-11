import { useEffect, useState } from 'react'
import { Modal, Tag } from 'antd'
import { gatewayApi, type ArtifactView, type ArtifactContentView } from '../api/gateway'

interface PreviewTarget {
  taskId: string
  artifact: ArtifactView
}

/**
 * 产物在线预览弹窗（V2.1）：结构化产物（json/markdown/html/text）拉取内容展示，
 * 超上限截断时提示走下载。JSON 自动 pretty-print。受控用法：open 由父组件传值。
 */
export default function ArtifactPreviewModal(
  { target, onClose }: { target: PreviewTarget | null; onClose: () => void },
) {
  const [view, setView] = useState<ArtifactContentView | null>(null)
  const [loading, setLoading] = useState(false)
  const open = target !== null

  useEffect(() => {
    if (!target) {
      setView(null)
      return
    }
    let cancelled = false
    setLoading(true)
    gatewayApi.adminArtifactContent(target.taskId, target.artifact.artifactId)
      .then((data) => { if (!cancelled) setView(data) })
      .catch(() => { if (!cancelled) onClose() })
      .finally(() => { if (!cancelled) setLoading(false) })
    return () => { cancelled = true }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [target?.taskId, target?.artifact.artifactId])

  function pretty(content: string, contentType: string | null | undefined): string {
    const mime = (contentType ?? '').toLowerCase()
    if (!mime.includes('json')) return content
    try {
      return JSON.stringify(JSON.parse(content), null, 2)
    } catch {
      return content
    }
  }

  return (
    <Modal
      open={open}
      title={`产物预览 · ${target?.artifact.artifactId ?? ''}`}
      width={720}
      onCancel={onClose}
      footer={[
        target?.artifact.url && (
          <a key="download" href={target.artifact.url} target="_blank" rel="noopener noreferrer">
            下载完整产物
          </a>
        ),
        <button key="close" className="ant-btn" onClick={onClose}>关闭</button>,
      ]}
    >
      <div style={{ minHeight: 120 }} data-testid="artifact-preview-dialog">
        {view && (
          <>
            <div className="preview-meta">
              <Tag>{view.type}</Tag>
              <span className="mono">{view.contentType ?? '-'}</span>
              <span>{view.sizeBytes ?? '-'} B</span>
              {view.truncated && <Tag color="warning">内容超上限已截断，完整内容请走下载</Tag>}
            </div>
            <pre className="preview-content" data-testid="artifact-preview-content">
              {pretty(view.content, view.contentType)}
            </pre>
          </>
        )}
      </div>
    </Modal>
  )
}
