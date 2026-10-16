package com.yangbin.yangbinaiagent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yangbin.yangbinaiagent.security.UserContext;
import com.yangbin.yangbinaiagent.skills.SkillService;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import static org.assertj.core.api.Assertions.*;

class SkillServiceTest {
    @TempDir Path directory;
    SkillService service;
    UserContext.Scope identity;
    @BeforeEach void setup() throws Exception {
        identity=UserContext.open(TaskWorkflowTest.A);
        service=new SkillService(new ObjectMapper(),directory.toString());
    }
    @AfterEach void close(){identity.close();}
    SkillService.Input input(String name,Integer version,boolean enabled){
        return new SkillService.Input(name,"个人雨天行程模板","优先安排室内活动并明确资料来源",true,true,version,enabled);
    }
    @Test void customSkillSurvivesRestartAndIsPrivateToOwner() throws Exception {
        var created=service.create(input("我的雨天技能",null,true));
        assertThat(created.builtin()).isFalse(); assertThat(created.version()).isEqualTo(1);
        service=new SkillService(new ObjectMapper(),directory.toString());
        assertThat(service.get(created.id())).isEqualTo(created);
        try(var ignored=UserContext.open(TaskWorkflowTest.B)) {
            assertThat(service.list()).noneMatch(s->s.id().equals(created.id()));
            assertThatThrownBy(()->service.get(created.id())).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(()->service.resolve(created.id())).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(()->service.update(created.id(),input("偷改",1,true))).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(()->service.delete(created.id(),1)).isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(service.get(created.id())).isEqualTo(created);
        try(var paths=Files.walk(directory)) {
            assertThat(paths.filter(Files::isRegularFile).map(p->p.getFileName().toString()).toList()).noneMatch(name->name.endsWith(".tmp"));
        }
    }
    @Test void versionsPreventLostUpdatesAndStaleDeletion() throws Exception {
        var original=service.create(input("原技能",null,true));
        var updated=service.update(original.id(),input("新版技能",1,true));
        assertThat(updated.version()).isEqualTo(2);
        assertThatThrownBy(()->service.update(original.id(),input("覆盖新版",1,true))).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(()->service.delete(original.id(),1)).isInstanceOf(IllegalStateException.class);
        assertThat(service.get(original.id())).isEqualTo(updated);
        service.delete(original.id(),2);
        assertThatThrownBy(()->service.get(original.id())).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void disabledSkillRemainsEditableButCannotBeResolvedForNewTask() throws Exception {
        var created=service.create(input("暂停技能",null,false));
        assertThat(service.get(created.id()).enabled()).isFalse();
        assertThatThrownBy(()->service.resolve(created.id())).isInstanceOfAny(IllegalArgumentException.class,IllegalStateException.class);
        var enabled=service.update(created.id(),input("恢复技能",1,true));
        assertThat(service.resolve(created.id())).isEqualTo(enabled);
    }
    @Test void builtinSkillsAreReadableAndCopyableButNeverMutable() throws Exception {
        for(String id:new String[]{"builtin-city","builtin-rain"}) {
            var builtin=service.get(id); assertThat(builtin.builtin()).isTrue(); assertThat(service.resolve(id)).isEqualTo(builtin);
            assertThatThrownBy(()->service.update(id,input("覆盖内置",builtin.version(),true))).isInstanceOfAny(IllegalArgumentException.class,IllegalStateException.class);
            assertThatThrownBy(()->service.delete(id,builtin.version())).isInstanceOfAny(IllegalArgumentException.class,IllegalStateException.class);
        }
        var source=service.get("builtin-rain");
        var copy=service.create(new SkillService.Input("雨天副本",source.description(),source.instructions(),source.useKnowledge(),source.exportPdf(),null,true));
        assertThat(copy.id()).isNotEqualTo(source.id()); assertThat(copy.builtin()).isFalse();
        service.delete(copy.id(),copy.version());
        assertThat(service.get("builtin-rain")).isEqualTo(source);
    }
    @Test void invalidInputAndPathTraversalNeverCreateStoredSkills() {
        assertThatThrownBy(()->service.create(input("  ",null,true))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->service.create(input("x".repeat(10000),null,true))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->service.create(new SkillService.Input("长指令","描述","x".repeat(100000),true,true,null,true))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->service.get("../../escape")).isInstanceOf(IllegalArgumentException.class);
        assertThat(service.list()).allMatch(SkillService.Skill::builtin);
    }
    @Test void updatesRequireVersionAndPreserveOriginalOnRejectedInput() throws Exception {
        var created=service.create(input("版本技能",null,true));
        assertThatThrownBy(()->service.update(created.id(),input("缺版本",null,true))).isInstanceOfAny(IllegalArgumentException.class,IllegalStateException.class);
        assertThatThrownBy(()->service.update(created.id(),input("",1,true))).isInstanceOf(IllegalArgumentException.class);
        assertThat(service.get(created.id())).isEqualTo(created);
    }
    @Test void personalSkillLimitDoesNotConsumeOtherAccountsQuota() throws Exception {
        for(int i=0;i<100;i++)service.create(input("技能"+i,null,true));
        assertThatThrownBy(()->service.create(input("第101个",null,true))).isInstanceOf(IllegalStateException.class);
        assertThat(service.list().stream().filter(s->!s.builtin())).hasSize(100);
        try(var ignored=UserContext.open(TaskWorkflowTest.B)) {
            assertThat(service.create(input("另一个账号",null,true)).version()).isEqualTo(1);
            assertThat(service.list().stream().filter(s->!s.builtin())).hasSize(1);
        }
    }

}
