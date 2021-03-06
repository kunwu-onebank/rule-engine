/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package com.ctrip.infosec.rule.executor;

import java.util.List;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.ctrip.infosec.common.Constants;
import com.ctrip.infosec.common.model.RiskFact;
import com.ctrip.infosec.configs.Configs;
import com.ctrip.infosec.configs.event.PostRule;
import com.ctrip.infosec.configs.rule.trace.logger.TraceLogger;
import com.ctrip.infosec.configs.rulemonitor.RuleMonitorHelper;
import com.ctrip.infosec.configs.rulemonitor.RuleMonitorType;
import com.ctrip.infosec.rule.Contexts;
import com.ctrip.infosec.rule.engine.StatelessPostRuleEngine;
import com.ctrip.infosec.sars.util.Collections3;
import com.ctrip.infosec.sars.util.SpringContextHolder;

/**
 *
 * @author zhengby
 */
@Service
public class PostRulesExecutorService {

    private static final Logger logger = LoggerFactory.getLogger(PostRulesExecutorService.class);

    /**
     * 执行预处理规则
     */
    public RiskFact executePostRules(RiskFact fact, boolean isAsync) {
        execute(fact, isAsync);
        return fact;
    }

    /**
     * 串行执行
     */
    void execute(RiskFact fact, boolean isAsync) {

        // matchRules      
        List<PostRule> matchedRules = Configs.matchPostRules(fact, isAsync);
        List<String> scriptRulePackageNames = Collections3.extractToList(matchedRules, "ruleNo");
        logger.debug(Contexts.getLogPrefix() + "matched post rules: " + StringUtils.join(scriptRulePackageNames, ", "));
        TraceLogger.traceLog("匹配到 " + matchedRules.size() + " 条后处理规则 ...");

        StatelessPostRuleEngine statelessPostRuleEngine = SpringContextHolder.getBean(StatelessPostRuleEngine.class);
        for (PostRule rule : matchedRules) {
            RuleMonitorHelper.newTrans(fact, RuleMonitorType.POST_RULE, rule.getRuleNo());
            TraceLogger.beginNestedTrans(fact.eventId);
            TraceLogger.setNestedLogPrefix("[" + rule.getRuleNo() + "]");
            Contexts.setPolicyOrRuleNo(rule.getRuleNo());
            try {
                long start = System.currentTimeMillis();

                // add current execute ruleNo and logPrefix before execution
                fact.ext.put(Constants.key_ruleNo, rule.getRuleNo());
                fact.ext.put(Constants.key_isAsync, isAsync);

                statelessPostRuleEngine.execute(rule.getRuleNo(), fact);

                // remove current execute ruleNo when finished execution.
                fact.ext.remove(Constants.key_ruleNo);
                fact.ext.remove(Constants.key_isAsync);

                long handlingTime = System.currentTimeMillis() - start;
                if (handlingTime > 100) {
                    logger.info(Contexts.getLogPrefix() + "postRule: " + rule.getRuleNo() + ", usage: " + handlingTime + "ms");
                }
                TraceLogger.traceLog("[" + rule.getRuleNo() + "] usage: " + handlingTime + "ms");

            } catch (Throwable ex) {
                logger.warn(Contexts.getLogPrefix() + "执行后处理规则异常. postRule: " + rule.getRuleNo(), ex);
                TraceLogger.traceLog("[" + rule.getRuleNo() + "] EXCEPTION: " + ex.toString());
            } finally {
                TraceLogger.commitNestedTrans();
                RuleMonitorHelper.commitTrans(fact);
                Contexts.clearLogPrefix();
            }
        }

    }
}
