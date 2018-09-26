/*
 * Copyright 1999-2018 Alibaba Group Holding Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.alibaba.csp.sentinel.slots.block.degrade;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.alibaba.csp.sentinel.context.Context;
import com.alibaba.csp.sentinel.log.RecordLog;
import com.alibaba.csp.sentinel.node.DefaultNode;
import com.alibaba.csp.sentinel.property.DynamicSentinelProperty;
import com.alibaba.csp.sentinel.property.PropertyListener;
import com.alibaba.csp.sentinel.property.SentinelProperty;
import com.alibaba.csp.sentinel.slotchain.ResourceWrapper;
import com.alibaba.csp.sentinel.slots.block.BlockException;
import com.alibaba.csp.sentinel.slots.block.flow.FlowRule;
import com.alibaba.csp.sentinel.util.StringUtil;

/***
 * @author youji.zj
 * @author jialiang.linjl
 */
public class DegradeRuleManager {

    private static volatile ConcurrentHashMap<String, List<DegradeRule>> degradeRules
        = new ConcurrentHashMap<String, List<DegradeRule>>();

    final static RulePropertyListener listener = new RulePropertyListener();
    private static SentinelProperty<List<DegradeRule>> currentProperty
        = new DynamicSentinelProperty<List<DegradeRule>>();

    static {
        currentProperty.addListener(listener);
    }

    /**
     * Listen to the {@link SentinelProperty} for {@link DegradeRule}s. The property is the source
     * of {@link DegradeRule}s. Degrade rules can also be set by {@link #loadRules(List)} directly.
     *
     * @param property the property to listen.
     */
    public static void register2Property(SentinelProperty<List<DegradeRule>> property) {
        synchronized (listener) {
            RecordLog.info("[DegradeRuleManager] Registering new property to degrade rule manager");
            currentProperty.removeListener(listener);
            property.addListener(listener);
            currentProperty = property;
        }
    }

    /*public static void checkDegrade(ResourceWrapper resource, Context context, DefaultNode node, int count)
        throws BlockException {
        if (degradeRules == null) {
            return;
        }

        List<DegradeRule> rules = degradeRules.get(resource.getName());
        if (rules == null) {
            return;
        }

        for (DegradeRule rule : rules) {
            if (!rule.passCheck(context, node, count)) {
                throw new DegradeException(rule.getLimitApp());
            }
        }
    }*/
    
	
	/** 修改支持全局规则 - shenjian **/
	private static final String GLOBAL = "*";

	public static void checkDegrade(ResourceWrapper resource, Context context, DefaultNode node, int count) throws BlockException {
		if (degradeRules == null) {
			return;
		}
		List<DegradeRule> rules = getRules(resource.getName());
		if (rules == null) {
			return;
		}
		for (DegradeRule rule : rules) {
			if (!rule.passCheck(context, node, count)) {
				throw new DegradeException(rule.getLimitApp());
			}
		}
	}

	private static List<DegradeRule> getRules(String resourceName) {
		List<DegradeRule> rules = degradeRules.get(resourceName);
		if (rules != null) {
			return rules;
		}
		rules = degradeRules.get(GLOBAL);
		if (rules == null || rules.isEmpty()) {
			return null;
		}
		// 使用全局规则初始化资源规则
		rules = copyRules(rules, resourceName);
		List<DegradeRule> oldRules = degradeRules.putIfAbsent(resourceName, rules);
		if (oldRules != null && !oldRules.equals(rules)) {// 没有添加成功：规则已存在
			return oldRules;
		}
		return rules;
	}

	private static List<DegradeRule> copyRules(List<DegradeRule> rules, String newResourceName) {
		List<DegradeRule> list = new ArrayList<DegradeRule>(rules.size());
		for (DegradeRule rule : rules) {
			list.add(copyRule(rule, newResourceName));
		}
		return list;
	}

	private static DegradeRule copyRule(DegradeRule rule, String newResourceName) {
		DegradeRule newRule = new DegradeRule();
		newRule.setResource(newResourceName);
		newRule.setCount(rule.getCount());
		newRule.setGrade(rule.getGrade());
		newRule.setLimitApp(rule.getLimitApp());
		newRule.setTimeWindow(rule.getTimeWindow());
		return newRule;
	}

	/** 修改支持全局规则 - end **/

    public static boolean hasConfig(String resource) {
        return degradeRules.containsKey(resource);
    }

    /**
     * Get a copy of the rules.
     *
     * @return a new copy of the rules.
     */
    public static List<DegradeRule> getRules() {
        List<DegradeRule> rules = new ArrayList<DegradeRule>();
        if (degradeRules == null) {
            return rules;
        }
        for (Map.Entry<String, List<DegradeRule>> entry : degradeRules.entrySet()) {
            rules.addAll(entry.getValue());
        }
        return rules;
    }
    
    

    /**
     * Load {@link DegradeRule}s, former rules will be replaced.
     *
     * @param rules new rules to load.
     */
    public static void loadRules(List<DegradeRule> rules) {
        try {
            currentProperty.updateValue(rules);
        } catch (Throwable e) {
            RecordLog.info(e.getMessage(), e);
        }
    }

    private static class RulePropertyListener implements PropertyListener<List<DegradeRule>> {

        @Override
        public void configUpdate(List<DegradeRule> conf) {
            Map<String, List<DegradeRule>> rules = loadDegradeConf(conf);
            if (rules != null) {
                degradeRules.clear();
                degradeRules.putAll(rules);
            }
            RecordLog.info("[DegradeRuleManager] Degrade rules received: " + degradeRules);
        }

        @Override
        public void configLoad(List<DegradeRule> conf) {
            Map<String, List<DegradeRule>> rules = loadDegradeConf(conf);
            if (rules != null) {
                degradeRules.clear();
                degradeRules.putAll(rules);
            }
            RecordLog.info("[DegradeRuleManager] Degrade rules loaded: " + degradeRules);
        }

        private Map<String, List<DegradeRule>> loadDegradeConf(List<DegradeRule> list) {
            Map<String, List<DegradeRule>> newRuleMap = new ConcurrentHashMap<String, List<DegradeRule>>();

            if (list == null || list.isEmpty()) {
                return newRuleMap;
            }

            for (DegradeRule rule : list) {
                if (!isValidRule(rule)) {
                    RecordLog.warn("[DegradeRuleManager] Ignoring invalid degrade rule when loading new rules: " + rule);
                    continue;
                }

                if (StringUtil.isBlank(rule.getLimitApp())) {
                    rule.setLimitApp(FlowRule.LIMIT_APP_DEFAULT);
                }

                String identity = rule.getResource();
                List<DegradeRule> ruleM = newRuleMap.get(identity);
                if (ruleM == null) {
                    ruleM = new ArrayList<DegradeRule>();
                    newRuleMap.put(identity, ruleM);
                }
                ruleM.add(rule);
            }

            return newRuleMap;
        }

    }

    private static boolean isValidRule(DegradeRule rule) {
        return rule != null && !StringUtil.isBlank(rule.getResource()) && rule.getCount() >= 0;
    }
}
