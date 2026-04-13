package lab.custody.domain.asset;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SupportedChainRepository extends JpaRepository<SupportedChain, String> {

    List<SupportedChain> findByEnabledTrue();
}
