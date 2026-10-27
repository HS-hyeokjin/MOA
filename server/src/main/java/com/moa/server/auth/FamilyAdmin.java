package com.moa.server.auth;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "moa.admin.command")
public class FamilyAdmin implements ApplicationRunner {
    private final AuthService auth;
    private final ConfigurableApplicationContext context;
    public FamilyAdmin(AuthService auth, ConfigurableApplicationContext context) { this.auth = auth; this.context = context; }
    @Override
    public void run(ApplicationArguments arguments) throws Exception {
        String command = context.getEnvironment().getRequiredProperty("moa.admin.command");
        String member = context.getEnvironment().getRequiredProperty("moa.admin.member");
        if (command.equals("invite")) {
            Path output = Path.of(context.getEnvironment().getRequiredProperty("moa.admin.output")).toAbsolutePath();
            if (Files.exists(output)) throw new IllegalArgumentException("Invite output already exists; choose a new private file.");
            Files.createDirectories(output.getParent());
            String displayName = context.getEnvironment().getProperty("moa.admin.display-name");
            if (displayName != null) auth.rename(member, displayName);
            var invite = auth.invite(member);
            Files.writeString(output, "member=" + invite.memberId() + "\ncode=" + invite.code()
                    + "\nexpiresAt=" + invite.expiresAt() + "\n", StandardOpenOption.CREATE_NEW);
        } else if (command.equals("revoke")) {
            auth.revoke(member);
        } else throw new IllegalArgumentException("Use invite or revoke.");
        context.close();
    }
}
