package com.zhiflow.common.data.cache;

import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 测试用事务管理器：不连库，只在「提交」那一刻执行一个钩子（把「未提交的写」变成「已提交」）。
 *
 * <p>事务同步（{@code TransactionSynchronizationManager} 的初始化、{@code afterCommit} 回调、清理）
 * 全部走 Spring 自己的 {@link AbstractPlatformTransactionManager}，与生产的 JDBC 事务管理器是同一套代码，
 * 所以「清缓存发生在提交之前还是之后」在这里测到的就是真实顺序。
 */
public final class CommitHookTransactionManager extends AbstractPlatformTransactionManager {

	private final Runnable onCommit;

	public CommitHookTransactionManager(Runnable onCommit) {
		this.onCommit = onCommit;
	}

	public TransactionTemplate template() {
		return new TransactionTemplate(this);
	}

	@Override
	protected Object doGetTransaction() {
		return new Object();
	}

	@Override
	protected void doBegin(Object transaction, TransactionDefinition definition) {
		// 不连库
	}

	@Override
	protected void doCommit(DefaultTransactionStatus status) {
		onCommit.run();
	}

	@Override
	protected void doRollback(DefaultTransactionStatus status) {
		// 未提交的写直接丢弃，由调用方的钩子模型决定
	}
}
