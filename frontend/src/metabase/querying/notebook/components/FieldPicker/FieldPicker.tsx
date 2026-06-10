import { useMemo, useState } from "react";
import { t } from "ttag";

import {
  HoverParent,
  QueryColumnInfoIcon,
} from "metabase/common/components/MetadataInfo/QueryColumnInfoIcon";
import { useTranslateContent } from "metabase/content-translation/hooks";
import { Checkbox, DelayGroup, Icon, TextInput } from "metabase/ui";
import * as Lib from "metabase-lib";

import S from "./FieldPicker.module.css";

export interface FieldPickerItem {
  column: Lib.ColumnMetadata;
  columnInfo: Lib.ColumnDisplayInfo;
}

interface FieldPickerProps {
  query: Lib.Query;
  stageIndex: number;
  columns: Lib.ColumnMetadata[];
  "data-testid"?: string;
  isColumnSelected: (
    item: FieldPickerItem,
    items: FieldPickerItem[],
  ) => boolean;
  isColumnDisabled?: (
    item: FieldPickerItem,
    items: FieldPickerItem[],
  ) => boolean;
  onToggle: (column: Lib.ColumnMetadata, isSelected: boolean) => void;
  onSelectAll: () => void;
  onSelectNone: () => void;
}

export const FieldPicker = ({
  query,
  stageIndex,
  columns,
  onToggle,
  onSelectAll,
  onSelectNone,
  isColumnSelected,
  isColumnDisabled,
  ...props
}: FieldPickerProps) => {
  const tc = useTranslateContent();
  const [searchText, setSearchText] = useState("");

  const items = useMemo(() => {
    const items = columns.map((column) => ({
      column,
      columnInfo: Lib.displayInfo(query, stageIndex, column),
    }));
    return items.map((item) => ({
      ...item,
      isSelected: isColumnSelected(item, items),
      isDisabled: isColumnDisabled?.(item, items),
    }));
  }, [query, stageIndex, columns, isColumnSelected, isColumnDisabled]);

  const visibleItems = searchText
    ? items.filter((item) =>
        item.columnInfo.displayName
          .toLowerCase()
          .includes(searchText.toLowerCase()),
      )
    : items;

  const isAll = items.every((item) => item.isSelected);
  const isNone = items.every((item) => !item.isSelected);

  const handleLabelToggle = () => {
    if (isAll) {
      onSelectNone();
    } else {
      onSelectAll();
    }
  };

  return (
    <div>
      <TextInput
        value={searchText}
        onChange={(e) => setSearchText(e.target.value)}
        rightSection={<Icon name="search" />}
        placeholder={t`Search for a column…`}
        mb="sm"
      />
      <ul className={S.ItemList} data-testid={props["data-testid"]}>
        <li className={S.ToggleItem}>
          <HoverParent as="label" className={S.Label}>
            <Checkbox
              variant="stacked"
              checked={isAll}
              indeterminate={!isAll && !isNone}
              onChange={handleLabelToggle}
            />
            <div className={S.ItemTitle}>{t`Select all`}</div>
          </HoverParent>
        </li>
        <DelayGroup>
          {visibleItems.map((item) => (
          <li key={item.columnInfo.longDisplayName}>
            <HoverParent className={S.Label} as="label">
              <Checkbox
                checked={item.isSelected}
                disabled={item.isDisabled}
                onChange={(event) =>
                  onToggle(item.column, event.target.checked)
                }
              />
              <QueryColumnInfoIcon
                className={S.ItemIcon}
                query={query}
                stageIndex={stageIndex}
                column={item.column}
                position="top-start"
                size={16}
              />
              <div className={S.ItemTitle}>
                {tc(item.columnInfo.displayName)}
              </div>
            </HoverParent>
          </li>
        ))}
        </DelayGroup>
      </ul>
    </div>
  );
};
