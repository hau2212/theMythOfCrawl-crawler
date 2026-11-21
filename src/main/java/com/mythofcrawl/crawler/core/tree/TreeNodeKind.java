package com.mythofcrawl.crawler.core.tree;

/**
 * Discriminator for polymorphic nodes inside the Tree-First Explorer.
 */
public enum TreeNodeKind {
    BRANCH,
    HELD_PENDING,
    API_GROUP,
    AGENT_ACTION,
    CLUSTER,
    GHOSTS
}
