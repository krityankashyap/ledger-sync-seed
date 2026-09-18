package in.simplifymoney.ledgersync.ingest;

import in.simplifymoney.ledgersync.model.Category;
import in.simplifymoney.ledgersync.model.Direction;
import in.simplifymoney.ledgersync.model.NormalizedTxn;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Assigns the final category to each transaction.
 *
 * MICRO and TRANSFER cannot be decided from one message alone:
 *  - MICRO is a UPI debit of Rs.100 or less.
 *  - TRANSFER is the user moving money between their OWN two accounts, which
 *    only shows up as a debit on one account matched by a credit on the other.
 *    The "SELF"/name text in the merchant lies (NEFT INWARD SELF has no second
 *    leg here; IMPS to RAHUL SHARMA is a real payment), so we match on the
 *    money, not the words.
 */

public class Categories {

  private Categories() {}

  private static final BigDecimal MICRO_MAX = new BigDecimal("100.00");
  private static final Duration TRANSFER_WINDOW = Duration.ofMinutes(15);

  public static List<NormalizedTxn> categorize(List<NormalizedTxn> txns) {
      // Everyone starts at the direction-based default.
      List<Category> cats = new ArrayList<>();
      for (NormalizedTxn t : txns) {
          cats.add(t.direction() == Direction.DEBIT ? Category.SPEND : Category.INCOME);
      }

      // Pass 1 - TRANSFER: pair each debit with an unused credit on the OTHER
      // account, same amount, within the time window. Mark both legs.
      boolean[] used = new boolean[txns.size()];
      for (int i = 0; i < txns.size(); i++) {
          NormalizedTxn debit = txns.get(i);
          if (debit.direction() != Direction.DEBIT || used[i]) continue;
          for (int j = 0; j < txns.size(); j++) {
              if (used[j]) continue;
              NormalizedTxn credit = txns.get(j);
              if (credit.direction() != Direction.CREDIT) continue;
              if (credit.accountLast4().equals(debit.accountLast4())) continue;
              if (credit.amount().compareTo(debit.amount()) != 0) continue;
              Duration gap = Duration.between(debit.occurredAt(), credit.occurredAt()).abs();
              if (gap.compareTo(TRANSFER_WINDOW) > 0) continue;

              cats.set(i, Category.TRANSFER);
              cats.set(j, Category.TRANSFER);
              used[i] = true;
              used[j] = true;
              break;
          }
      }

      // Pass 2 - MICRO: a UPI debit of Rs.100 or less that is still SPEND.
      for (int i = 0; i < txns.size(); i++) {
          if (cats.get(i) != Category.SPEND) continue;
          NormalizedTxn t = txns.get(i);
          if (t.direction() == Direction.DEBIT
                  && t.amount().compareTo(MICRO_MAX) <= 0
                  && t.merchant() != null
                  && t.merchant().toUpperCase().contains("UPI")) {
                    cats.set(i, Category.MICRO);
                }
            }
    
            // Rebuild the list with the decided categories (NormalizedTxn is frozen).
            List<NormalizedTxn> out = new ArrayList<>(txns.size());
            for (int i = 0; i < txns.size(); i++) {
                out.add(withCategory(txns.get(i), cats.get(i)));
            }
            return out;
        }
    
        private static NormalizedTxn withCategory(NormalizedTxn t, Category c) {
            return new NormalizedTxn(t.accountLast4(), t.occurredAt(), t.direction(),
                    t.amount(), c, t.merchant(), t.sourceMessageIds());
        }
    }
  

