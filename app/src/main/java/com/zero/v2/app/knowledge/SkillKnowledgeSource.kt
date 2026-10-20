package com.zero.v2.app.knowledge

import com.zero.v2.app.data.WorkDirRepository
import com.zero.v2.app.skills.SkillStore

/** Private skill Markdown participates in the same rebuildable KB index. */
internal class SkillKnowledgeSource(private val base: KnowledgeSource, private val skills: SkillStore) : KnowledgeSource {
    override fun identity() = base.identity() + "|private-skills-v1"
    override fun hasDirectory() = true
    override suspend fun createFolder(path: String) = if (base.hasDirectory()) base.createFolder(path) else path
    override suspend fun listTree(): WorkDirRepository.TreeListing {
        val original = if (base.hasDirectory()) base.listTree() else WorkDirRepository.TreeListing(emptyList(), true)
        check(original.nodes.none { it.path.startsWith(SkillStore.PREFIX) }) { "reserved_skill_path_collision" }
        return original.copy(nodes = original.nodes + skills.list().map {
            WorkDirRepository.TreeNode(SkillStore.PREFIX + "${it.id}.md", "${it.id}.md", false,
                it.markdown.toByteArray(Charsets.UTF_8).size.toLong(), it.modifiedAt)
        })
    }
    override suspend fun readText(path: String): String? = if (path.startsWith(SkillStore.PREFIX))
        skills.read(path.removePrefix(SkillStore.PREFIX).removeSuffix(".md"))?.markdown else base.readText(path)
}
