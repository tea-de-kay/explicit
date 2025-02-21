/*
 * Copyright (c) 2016-2018, Leftshift One
 * __________________
 * [2018] Leftshift One
 * All Rights Reserved.
 * NOTICE:  All information contained herein is, and remains
 * the property of Leftshift One and its suppliers,
 * if any.  The intellectual and technical concepts contained
 * herein are proprietary to Leftshift One
 * and its suppliers and may be covered by Patents,
 * patents in process, and are protected by trade secret or copyright law.
 * Dissemination of this information or reproduction of this material
 * is strictly forbidden unless prior written permission is obtained
 * from Leftshift One.
 */

package explicit.evaluator

import explicit.api.IToken
import explicit.api.Navigator
import explicit.parser.ExplicitRule
import explicit.parser.ExplicitRules
import explicit.token.Alias
import explicit.token.Not
import explicit.token.Optional
import explicit.token.Wildcard
import explicit.token.tokenizer.CharacterCaseTokenizer
import java.util.concurrent.atomic.AtomicBoolean

class ExplicitRuleEvaluator(val rulez: ExplicitRules, val rule: ExplicitRule) {

    fun evaluate(text: String, excludes: Set<List<Int>> = HashSet()): ExplicitRuleEvaluation {
        if (rule.rule.all { e -> e is Wildcard })
            return ExplicitRuleEvaluation(true, HashMap(), ArrayList(HashSet()))

        val tokenCount = rule.rule.filter { e -> e !is Wildcard && e !is Not }.count()

        val wildcard = AtomicBoolean(false)
        val wildcardCollection = AtomicBoolean(false)
        val candidate = AtomicBoolean(false)

        val textNav = Navigator(rule.rule)
        val ruleNav = Navigator(CharacterCaseTokenizer().tokenize(text, rulez.tokens))
        // if (index > 0)
        //     ruleNav.setIndex(index + 1)
        val entries = HashMap<String, String>()
        val negations = ArrayList<IToken>()
        val indices = ArrayList<Int>()
        val wildcardCollector = HashMap<String, ArrayList<String>>()

        while (textNav.hasNext()) {
            var token = textNav.next()
            val isOptional = token is Optional || (token is Alias && token.token is Optional)

            if (token is Wildcard) {
                wildcard.set(true)
                continue
            }
            if (token is Alias && token.token is Wildcard) {
                if (textNav.hasNext()) {
                    val collector = ArrayList<String>()
                    while (ruleNav.hasNext()) {
                        ruleNav.next()
                        collector.add(ruleNav.getCurr())
                        indices.add(ruleNav.getIndex())
                    }
                    entries[token.alias] = collector.joinToString(" ")
                }
                wildcard.set(true)
                wildcardCollection.set(true)
                continue
            }

            if (token is Not) {
                negations.add(token.token)
                continue
            }
            if (!ruleNav.hasNext()) {
                if (isOptional)
                    continue
                return ExplicitRuleEvaluation(false, entries, ArrayList())
            }
            while (ruleNav.hasNext()) {
                ruleNav.next()
                val isNegated = negations.any { n -> n.evaluate(rulez, ruleNav).result }
                if (isNegated)
                    return ExplicitRuleEvaluation(false, entries, ArrayList())

                var evaluation = token.evaluate(rulez, ruleNav)

                // navigate optionals during wildcard search
                if (wildcardCollection.get() && isOptional && !evaluation.result) {
                    val currIndex = textNav.getIndex()
                    var stopWildcardCollection = false

                    var tmpToken = token
                    while (tmpToken is Optional) {
                        tmpToken = textNav.next()
                        val tmpEvaluation = tmpToken.evaluate(rulez, ruleNav)

                        if (tmpEvaluation.result) {
                            token = tmpToken
                            evaluation = tmpEvaluation
                            stopWildcardCollection = true
                        }
                    }

                    // reset index if the wildcard matches the token
                    if (!stopWildcardCollection) {
                        textNav.setIndex(currIndex)
                    }
                }

                if (containsAny(excludes, evaluation.indices)) {
                    candidate.set(false)
                    textNav.reset()
                    break
                }

                entries.putAll(evaluation.entries)
                val (result, _, indices1) = evaluation
                candidate.compareAndSet(false, result)

                if (result) {
                    wildcard.set(false)
                    wildcardCollection.set(false)

                    wildcardCollector.forEach { k, v -> entries[k] = v.joinToString(" ") }

                    if (rule.idx.isEmpty() || rule.idx.contains(textNav.getIndex() - 1)) {
                        indices.addAll(indices1)
                    }
                    break
                }
                if (!result && isOptional && !wildcardCollection.get()) {
                    ruleNav.prev()
                    break
                }
                if (!result && !wildcard.get()) {
                    // check how much words are remaining and reset the first navigator
                    if (ruleNav.getRemaining() >= tokenCount - 1 && !isNegated) {
                        candidate.set(false)
                        textNav.reset()
                        break
                    }
                    return ExplicitRuleEvaluation(false, entries, ArrayList())
                }
                if (!result && wildcardCollection.get()) {
                    if (textNav.getPrev().isPresent && textNav.getPrev().get() is Alias) {
                        val alias = (textNav.getPrev().get() as Alias).alias
                        wildcardCollector.putIfAbsent(alias, ArrayList())
                        wildcardCollector[alias]?.add(ruleNav.getCurr())
                    }
                }
            }
        }
        return ExplicitRuleEvaluation(candidate.get(), entries, ArrayList(HashSet(indices)))
    }

    private fun containsAny(set: Set<List<Int>>, list: List<Int>): Boolean {
        for (exclude in set) {
            for (index in list) {
                if (exclude.contains(index)) {
                    return true
                }
            }
        }
        return false
    }

}
